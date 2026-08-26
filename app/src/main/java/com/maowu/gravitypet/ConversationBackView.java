package com.maowu.gravitypet;

import android.content.Context;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/** A one-pixel, non-touchable overlay that owns only the system back action. */
final class ConversationBackView extends View {
    private final Runnable onBack;
    private OnBackInvokedDispatcher backDispatcher;
    private OnBackInvokedCallback backCallback;

    ConversationBackView(Context context, Runnable onBack) {
        super(context);
        this.onBack = onBack;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setContentDescription("返回键结束对话");
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestFocus();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backDispatcher = findOnBackInvokedDispatcher();
            if (backDispatcher != null) {
                backCallback = onBack::run;
                backDispatcher.registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCallback);
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && backDispatcher != null && backCallback != null) {
            backDispatcher.unregisterOnBackInvokedCallback(backCallback);
        }
        backDispatcher = null;
        backCallback = null;
        super.onDetachedFromWindow();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) onBack.run();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
