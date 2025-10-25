package com.leadrdrk.umapatcher.shizuku;

import android.content.IIntentReceiver;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

import android.content.IIntentSender;

public abstract class IIntentSenderAdaptor extends IIntentSender.Stub {

    public abstract void send(Intent intent);

    @Override
    public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken, IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
        send(intent);
    }
}