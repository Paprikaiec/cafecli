package com.cafecli.render.inline;

/** Explicit fold choice shared by the live strip and transcript overlay. */
enum FoldState {
    COLLAPSED,
    EXPANDED;

    FoldState toggle() {
        return this == COLLAPSED ? EXPANDED : COLLAPSED;
    }
}
