package com.tvbox.mobileemu;

import android.view.Surface;

interface IRootDisplayService {
    int createVirtualDisplay(in Surface surface, int width, int height, int dpi);
    void releaseDisplay();
    void launchAppOnDisplay(String packageName, int displayId);
    void injectTouch(int action, float x, float y, int displayId, long downTime, long eventTime);
    void injectBackKey(int displayId);
    String installApkAndGrant(String apkPath);
}
