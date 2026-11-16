package com.gathin.tvhome;

/** Shared row geometry keeps the short-list footer consistent with the actual decorations. */
final class SelectionLayout {
    private SelectionLayout() {}

    static int dockBottomOffsetPx(int tileHeight, int itemHeight, float density) {
        return Math.round(24.2f * density + (116 * density - tileHeight) / 2f) - (itemHeight - tileHeight);
    }

    static int homeGridOffsetPx(int travel, int selectionTravel, float density) {
        float remaining = 1f - Math.min(1f, Math.max(0, travel) / (float) Math.max(1, selectionTravel));
        return -Math.round((24.2f - 20.2f) * density * remaining);
    }

    static int rowBottomOffsetPx(int tileHeight, int itemHeight, float density) {
        return Math.round(44.4f * density) - (itemHeight - tileHeight);
    }

    static int libraryHeightPx(int height, int tileHeight, int itemHeight, int rows, float density) {
        int firstItemTop = (int) Math.floor(32 * density + (116 * density - tileHeight) / 2f - 8 * density);
        int dockPitch = itemHeight + dockBottomOffsetPx(tileHeight, itemHeight, density);
        int rowPitch = itemHeight + rowBottomOffsetPx(tileHeight, itemHeight, density);
        int footerTop = firstItemTop + dockPitch + Math.max(0, rows - 1) * rowPitch;
        return Math.max(Math.round(84 * density), height - Math.round(20 * density) - footerTop);
    }
}
