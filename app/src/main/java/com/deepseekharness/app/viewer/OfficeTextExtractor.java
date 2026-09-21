package com.deepseekharness.app.viewer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Office (docx / xlsx / doc) 纯文本与表格数据极速抽取器：
 * 1. docx / xlsx：基于 Android 原生 XmlPullParser 抽取 XML；
 * 2. doc (OLE2 二进制)：基于二进制流扫描与 UTF-16LE / ASCII 嗅探，毫秒级提取正文。
 */
public final class OfficeTextExtractor {

    public static final int MAX_TEXT_CHARS = 2_000_000;

    /** 提取 docx 纯文本 */
    public static String extractDocx(File file) {
        if (file == null || !file.isFile()) return null;
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                return parseDocxXml(in);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 提取老旧二进制 doc 格式文本（方案 A：纯原生 OLE2 二进制流字符嗅探） */
    public static String extractDoc(File file) {
        if (file == null || !file.isFile() || file.length() <= 512) return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            int len = (int) Math.min(file.length(), 4 * 1024 * 1024); // 最多读取前 4MB
            byte[] bytes = new byte[len];
            int read = 0;
            while (read < len) {
                int n = fis.read(bytes, read, len - read);
                if (n <= 0) break;
                read += n;
            }

            StringBuilder sb = new StringBuilder();
            // 双字节 UTF-16LE 扫描（中文与现代 Word 正文最常用编码）
            StringBuilder segment = new StringBuilder();
            for (int i = 512; i < read - 1; i += 2) {
                char c = (char) ((bytes[i] & 0xFF) | ((bytes[i + 1] & 0xFF) << 8));
                if (isReadableChar(c)) {
                    segment.append(c);
                } else {
                    if (segment.length() >= 3) {
                        sb.append(segment).append("\n");
                    }
                    segment.setLength(0);
                }
                if (sb.length() >= MAX_TEXT_CHARS) break;
            }
            if (segment.length() >= 3) {
                sb.append(segment).append("\n");
            }

            // 若 UTF-16LE 提取内容较少，尝试单字节 ASCII 扫描兜底（针对纯英文老旧文档）
            if (sb.length() < 20) {
                sb.setLength(0);
                segment.setLength(0);
                for (int i = 512; i < read; i++) {
                    int b = bytes[i] & 0xFF;
                    if ((b >= 32 && b <= 126) || b == 10 || b == 13 || b == 9) {
                        segment.append((char) b);
                    } else {
                        if (segment.length() >= 4) {
                            sb.append(segment).append("\n");
                        }
                        segment.setLength(0);
                    }
                    if (sb.length() >= MAX_TEXT_CHARS) break;
                }
                if (segment.length() >= 4) {
                    sb.append(segment).append("\n");
                }
            }

            return sb.length() > 0 ? sb.toString().trim() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isReadableChar(char c) {
        // 中文汉字区 (0x4E00 - 0x9FA5) 与常用全角标点
        if (c >= 0x4E00 && c <= 0x9FA5) return true;
        if (c >= 0x3000 && c <= 0x303F) return true;
        if (c >= 0xFF01 && c <= 0xFF5E) return true;
        // 常用 ASCII 可见字符与换行空格
        if (c >= 0x20 && c <= 0x7E) return true;
        if (c == '\n' || c == '\r' || c == '\t') return true;
        return false;
    }

    /** 提取 xlsx 工作表与单元格数据 */
    public static String extractXlsx(File file) {
        if (file == null || !file.isFile()) return null;
        try (ZipFile zip = new ZipFile(file)) {
            // 1. 读取共享字符串表 sharedStrings.xml
            List<String> sharedStrings = new ArrayList<>();
            ZipEntry sstEntry = zip.getEntry("xl/sharedStrings.xml");
            if (sstEntry != null) {
                try (InputStream in = zip.getInputStream(sstEntry)) {
                    sharedStrings = parseSharedStrings(in);
                }
            }

            // 2. 依次读取各个工作表 sheet1.xml, sheet2.xml...
            StringBuilder result = new StringBuilder();
            int sheetIdx = 1;
            while (true) {
                ZipEntry sheetEntry = zip.getEntry("xl/worksheets/sheet" + sheetIdx + ".xml");
                if (sheetEntry == null) break;
                if (sheetIdx > 1) {
                    result.append("\n\n--- 工作表 ").append(sheetIdx).append(" ---\n\n");
                }
                try (InputStream in = zip.getInputStream(sheetEntry)) {
                    parseSheetXml(in, sharedStrings, result);
                }
                sheetIdx++;
                if (result.length() >= MAX_TEXT_CHARS) break;
            }

            return result.length() > 0 ? result.toString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String parseDocxXml(InputStream in) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(in, "UTF-8");

        StringBuilder sb = new StringBuilder();
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if ("p".equals(name)) {
                    // 段落开始
                } else if ("t".equals(name)) {
                    // 文本节点
                    String text = parser.nextText();
                    if (text != null) sb.append(text);
                } else if ("tab".equals(name)) {
                    sb.append("\t");
                } else if ("br".equals(name) || "cr".equals(name)) {
                    sb.append("\n");
                }
            } else if (event == XmlPullParser.END_TAG) {
                if ("p".equals(parser.getName())) {
                    sb.append("\n");
                }
            }
            if (sb.length() >= MAX_TEXT_CHARS) {
                sb.append("\n\n(已截断后续内容)");
                break;
            }
            event = parser.next();
        }
        return sb.toString();
    }

    private static List<String> parseSharedStrings(InputStream in) throws Exception {
        List<String> list = new ArrayList<>();
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(in, "UTF-8");

        StringBuilder curStr = null;
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if ("si".equals(name)) {
                    curStr = new StringBuilder();
                } else if ("t".equals(name) && curStr != null) {
                    String t = parser.nextText();
                    if (t != null) curStr.append(t);
                }
            } else if (event == XmlPullParser.END_TAG) {
                if ("si".equals(parser.getName()) && curStr != null) {
                    list.add(curStr.toString());
                    curStr = null;
                }
            }
            event = parser.next();
        }
        return list;
    }

    private static void parseSheetXml(InputStream in, List<String> sst, StringBuilder out) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(in, "UTF-8");

        String cellType = null;
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if ("c".equals(name)) {
                    cellType = parser.getAttributeValue(null, "t");
                } else if ("v".equals(name)) {
                    String val = parser.nextText();
                    if (val != null) {
                        if ("s".equals(cellType)) {
                            try {
                                int idx = Integer.parseInt(val.trim());
                                if (idx >= 0 && idx < sst.size()) {
                                    out.append(sst.get(idx));
                                } else {
                                    out.append(val);
                                }
                            } catch (Exception e) {
                                out.append(val);
                            }
                        } else {
                            out.append(val);
                        }
                    }
                    out.append("\t");
                }
            } else if (event == XmlPullParser.END_TAG) {
                if ("row".equals(parser.getName())) {
                    out.append("\n");
                }
            }
            if (out.length() >= MAX_TEXT_CHARS) break;
            event = parser.next();
        }
    }
}
