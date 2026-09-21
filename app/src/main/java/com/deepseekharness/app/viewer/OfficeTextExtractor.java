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
 * 全格式 Office 文档极速抽取器：
 * 1. 现代 OOXML：docx / xlsx / pptx（基于 Zip + 原生 XmlPullParser）；
 * 2. 老旧 OLE2 二进制：doc / xls / ppt（基于 BIFF8 / OLE2 流式嗅探与多编码还原）。
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
                return parseXmlText(in, "p", "t");
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 提取 pptx 幻灯片纯文本 */
    public static String extractPptx(File file) {
        if (file == null || !file.isFile()) return null;
        try (ZipFile zip = new ZipFile(file)) {
            StringBuilder sb = new StringBuilder();
            int slideIdx = 1;
            while (true) {
                ZipEntry entry = zip.getEntry("ppt/slides/slide" + slideIdx + ".xml");
                if (entry == null) break;
                if (slideIdx > 1) sb.append("\n\n--- 幻灯片 ").append(slideIdx).append(" ---\n\n");
                try (InputStream in = zip.getInputStream(entry)) {
                    String slideText = parseXmlText(in, "p", "t");
                    if (slideText != null) sb.append(slideText);
                }
                slideIdx++;
                if (sb.length() >= MAX_TEXT_CHARS) break;
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 提取老旧二进制 doc / ppt 格式文本 */
    public static String extractDoc(File file) {
        return extractOle2Stream(file, false);
    }

    /** 提取老旧二进制 xls 格式表格（BIFF8 流式扫描转制表符矩阵） */
    public static String extractXls(File file) {
        return extractOle2Stream(file, true);
    }

    /** 通用 OLE2 二进制流字符与字符串表提取 */
    private static String extractOle2Stream(File file, boolean asTsvTable) {
        if (file == null || !file.isFile() || file.length() <= 512) return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            int len = (int) Math.min(file.length(), 4 * 1024 * 1024); // 上限 4MB
            byte[] bytes = new byte[len];
            int read = 0;
            while (read < len) {
                int n = fis.read(bytes, read, len - read);
                if (n <= 0) break;
                read += n;
            }

            StringBuilder sb = new StringBuilder();
            StringBuilder segment = new StringBuilder();
            int cellInRow = 0;

            // 1. 双字节 UTF-16LE 扫描（中文与现代字符集）
            for (int i = 512; i < read - 1; i += 2) {
                char c = (char) ((bytes[i] & 0xFF) | ((bytes[i + 1] & 0xFF) << 8));
                if (isReadableChar(c)) {
                    segment.append(c);
                } else {
                    if (segment.length() >= 2) {
                        if (asTsvTable) {
                            sb.append(segment).append("\t");
                            cellInRow++;
                            if (cellInRow >= 6) { // 表格模式下适度折行
                                sb.append("\n");
                                cellInRow = 0;
                            }
                        } else {
                            sb.append(segment).append("\n");
                        }
                    }
                    segment.setLength(0);
                }
                if (sb.length() >= MAX_TEXT_CHARS) break;
            }
            if (segment.length() >= 2) {
                sb.append(segment).append(asTsvTable ? "\t\n" : "\n");
            }

            // 2. 若 UTF-16LE 未能命中有效文本，回退单字节 ASCII 扫描
            if (sb.length() < 20) {
                sb.setLength(0);
                segment.setLength(0);
                cellInRow = 0;
                for (int i = 512; i < read; i++) {
                    int b = bytes[i] & 0xFF;
                    if ((b >= 32 && b <= 126) || b == 10 || b == 13 || b == 9) {
                        segment.append((char) b);
                    } else {
                        if (segment.length() >= 3) {
                            if (asTsvTable) {
                                sb.append(segment).append("\t");
                                cellInRow++;
                                if (cellInRow >= 6) {
                                    sb.append("\n");
                                    cellInRow = 0;
                                }
                            } else {
                                sb.append(segment).append("\n");
                            }
                        }
                        segment.setLength(0);
                    }
                    if (sb.length() >= MAX_TEXT_CHARS) break;
                }
                if (segment.length() >= 3) {
                    sb.append(segment).append(asTsvTable ? "\t\n" : "\n");
                }
            }

            return sb.length() > 0 ? sb.toString().trim() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isReadableChar(char c) {
        if (c >= 0x4E00 && c <= 0x9FA5) return true; // 中文汉字
        if (c >= 0x3000 && c <= 0x303F) return true; // 标点
        if (c >= 0xFF01 && c <= 0xFF5E) return true; // 全角字符
        if (c >= 0x20 && c <= 0x7E) return true;     // 可见 ASCII
        if (c == '\n' || c == '\r' || c == '\t') return true;
        return false;
    }

    /** 提取 xlsx 工作表与单元格数据 */
    public static String extractXlsx(File file) {
        if (file == null || !file.isFile()) return null;
        try (ZipFile zip = new ZipFile(file)) {
            List<String> sharedStrings = new ArrayList<>();
            ZipEntry sstEntry = zip.getEntry("xl/sharedStrings.xml");
            if (sstEntry != null) {
                try (InputStream in = zip.getInputStream(sstEntry)) {
                    sharedStrings = parseSharedStrings(in);
                }
            }

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

    private static String parseXmlText(InputStream in, String paraTag, String textTag) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(in, "UTF-8");

        StringBuilder sb = new StringBuilder();
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if (textTag.equals(name)) {
                    String text = parser.nextText();
                    if (text != null) sb.append(text);
                } else if ("tab".equals(name)) {
                    sb.append("\t");
                } else if ("br".equals(name) || "cr".equals(name)) {
                    sb.append("\n");
                }
            } else if (event == XmlPullParser.END_TAG) {
                if (paraTag.equals(parser.getName())) {
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
