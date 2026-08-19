package com.deepseekharness.app;

import android.view.View;

import androidx.fragment.app.Fragment;

final class SubPageBack {
    private SubPageBack() {}

    static void bind(Fragment fragment, View root) {
        View back = root.findViewById(R.id.sub_back);
        if (back == null) return;
        if (fragment.getParentFragmentManager().getBackStackEntryCount() > 0) {
            back.setVisibility(View.VISIBLE);
            back.setOnClickListener(v -> fragment.getParentFragmentManager().popBackStack());
        }
    }
}
