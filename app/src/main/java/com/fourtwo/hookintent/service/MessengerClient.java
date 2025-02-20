package com.fourtwo.hookintent.service;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MessengerClient {
    private static final String TAG = "MessengerClient";

    private final Context context;
    private Messenger serviceMessenger;
    private boolean isBound = false;

    private final AtomicInteger requestIdGenerator = new AtomicInteger(0);
    private final Messenger clientMessenger = new Messenger(new IncomingHandler());
    private final ConcurrentHashMap<Integer, CompletableFuture<Bundle>> pendingRequests = new ConcurrentHashMap<>();

    // 回调接口
    public interface ResultCallback {
        void onResult(Bundle result);

        void onError(Exception e);
    }

    public interface PassiveCallback {
        void onMessageReceived(Bundle data);
    }

    private PassiveCallback passiveCallback;

    // 客户端消息接收 Handler
    @SuppressLint("HandlerLeak")
    private class IncomingHandler extends Handler {
        public IncomingHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MessengerService.MSG_REPLY) {
                Bundle bundle = msg.getData();
                int requestId = bundle.getInt("requestId");

                Log.d(TAG, "Received reply for requestId=" + requestId);

                CompletableFuture<Bundle> future = pendingRequests.remove(requestId);
                if (future != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        future.complete(bundle);
                    }
                }
            } else if (msg.what == MessengerService.MSG_NOTIFY_HOOK_STATE) {
                if (passiveCallback != null) {
                    passiveCallback.onMessageReceived(msg.getData());
                }
            } else {
                super.handleMessage(msg);
            }
        }
    }

    public MessengerClient(Context context) {
        this.context = context;
    }

    // 注册被动回调
    public void registerPassiveCallback(PassiveCallback callback) {
        this.passiveCallback = callback;
        bindService(); // 绑定服务并注册客户端
    }

    // 同步绑定服务（内部使用）
    private void bindService() {
        Intent intent = new Intent();
        intent.setClassName("com.fourtwo.hookintent", "com.fourtwo.hookintent.service.MessengerService");

        context.bindService(intent, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                serviceMessenger = new Messenger(binder);
                isBound = true;

                // 注册客户端
                try {
                    Message msg = Message.obtain(null, MessengerService.MSG_REGISTER_CLIENT);
                    msg.replyTo = clientMessenger;
                    serviceMessenger.send(msg);
                    Log.d(TAG, "Client registered with service");
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to register client with service", e);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                serviceMessenger = null;
                isBound = false;
                Log.d(TAG, "Service disconnected");
            }
        }, Context.BIND_AUTO_CREATE);
    }

    // 解绑服务并取消注册客户端
    public void unbindService() {
        if (isBound) {
            try {
                Message msg = Message.obtain(null, MessengerService.MSG_UNREGISTER_CLIENT);
                msg.replyTo = clientMessenger;
                serviceMessenger.send(msg);
                Log.d(TAG, "Client unregistered from service");
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to unregister client from service", e);
            }

            context.unbindService((ServiceConnection) serviceMessenger.getBinder());
            isBound = false;
        }
    }

    // 异步发送消息
    public void sendMessageAsync(int messageType, Bundle data, boolean needCallback, @Nullable ResultCallback callback) {
        // 提供现有的异步调用方式，保持原有功能不变
        Intent intent = new Intent();
        intent.setClassName("com.fourtwo.hookintent", "com.fourtwo.hookintent.service.MessengerService");

        context.bindService(intent, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                serviceMessenger = new Messenger(binder);

                // 如果需要回调，生成 requestId 和 Future
                int requestId = 0;
                CompletableFuture<Bundle> future = null;
                if (needCallback) {
                    requestId = requestIdGenerator.incrementAndGet();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        future = new CompletableFuture<>();
                    }
                    pendingRequests.put(requestId, future);
                }

                Message msg = Message.obtain(null, messageType);
                if (needCallback) {
                    msg.arg1 = requestId; // 如果需要回调，设置 requestId
                }
                if (data != null) {
                    msg.setData(data);
                }
                msg.replyTo = needCallback ? clientMessenger : null; // 如果不需要回调，不设置 replyTo

                try {
                    serviceMessenger.send(msg);
                } catch (RemoteException e) {
                    if (needCallback) {
                        pendingRequests.remove(requestId);
                    }
                    if (callback != null) {
                        callback.onError(new RuntimeException("Failed to send message to service", e));
                    }
                }

                if (needCallback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    future.whenComplete((result, throwable) -> {
                        if (throwable != null) {
                            if (callback != null) {
                                callback.onError(new RuntimeException("Failed to get reply from service", throwable));
                            }
                        } else {
                            if (callback != null) {
                                callback.onResult(result);
                            }
                        }
                    });
                }

                context.unbindService(this);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                serviceMessenger = null;
            }
        }, Context.BIND_AUTO_CREATE);
    }

}
