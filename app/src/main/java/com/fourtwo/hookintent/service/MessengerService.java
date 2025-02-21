package com.fourtwo.hookintent.service;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.fourtwo.hookintent.utils.HookStatusManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MessengerService extends Service {
    private static final String TAG = "MessengerService";

    // 消息类型
    public static final int MSG_IS_HOOK = 1;  // 检查是否 Hook
    public static final int MSG_GET_DATA = 2; // 获取数据

    public static final int MSG_SEND_DATA = 3;  // 发送数据

    public static final int MSG_REPLY = 99;  // 回复消息
    public static final int MSG_REGISTER_CLIENT = 100; // 客户端注册
    public static final int MSG_UNREGISTER_CLIENT = 101; // 客户端取消注册
    public static final int MSG_NOTIFY_HOOK_STATE = 102; // 通知 Hook 状态变化
    private static MessengerService instance;

    // 用于缓存接收到的每条数据
    private final ConcurrentLinkedQueue<Bundle> dataQueue = new ConcurrentLinkedQueue<>();

    public static final MutableLiveData<Boolean> liveDataTrigger = new MutableLiveData<>(); // 用于通知观察者处理队列


    public ConcurrentLinkedQueue<Bundle> getDataQueue() {
        return dataQueue;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this; // 初始化单例实例
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null; // 销毁时清空实例
    }

    // 提供静态方法获取服务实例
    public static MessengerService getInstance() {
        return instance;
    }

    // 客户端列表
    private final List<Messenger> clients = new ArrayList<>();

    // 服务端处理消息的 Handler
    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {

            switch (msg.what) {
                case MSG_SEND_DATA:
                    // 接收数据并加入队列
                    Bundle data = msg.getData();
                    Log.d(TAG, "Received data: " + data);

                    // 将数据加入队列
                    dataQueue.add(data);

                    // 通知观察者
                    liveDataTrigger.postValue(true);
                    break;
                case MSG_IS_HOOK:
                    boolean isHooked = HookStatusManager.isHook();
                    // 返回结果
                    sendReply(msg.replyTo, msg.what, msg.arg1, isHooked ? 1 : 0, "Hook status checked");
                    break;

                case MSG_GET_DATA:
                    // 模拟获取数据
                    String data1 = getData();
                    Bundle m = msg.getData();
                    m.getString("Base");
                    Log.d(TAG, "handleMessage: " + m);
                    // 返回结果
                    sendReply(msg.replyTo, msg.what, msg.arg1, 0, data1);
                    break;

                case MSG_REGISTER_CLIENT:
                    // 注册客户端
                    clients.add(msg.replyTo);
                    Log.d(TAG, "Client registered: " + msg.replyTo);
                    break;

                case MSG_UNREGISTER_CLIENT:
                    // 取消注册客户端
                    clients.remove(msg.replyTo);
                    Log.d(TAG, "Client unregistered: " + msg.replyTo);
                    break;

                default:
                    super.handleMessage(msg);
            }
        }
    };

    private final Messenger messenger = new Messenger(handler);

    @Override
    public android.os.IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    private String getData() {
        // 模拟返回数据
        return "Sample Data";
    }

    private void sendReply(Messenger clientMessenger, int requestType, int requestId, int resultCode, String resultData) {
        if (clientMessenger != null) {
            try {
                Message reply = Message.obtain(null, MSG_REPLY);

                // 将结果封装到 Bundle 中
                Bundle bundle = new Bundle();
                bundle.putInt("requestType", requestType); // 请求类型
                bundle.putInt("requestId", requestId);     // 请求 ID
                bundle.putInt("resultCode", resultCode);   // 结果代码
                bundle.putString("resultData", resultData); // 结果数据
                reply.setData(bundle);
//                Log.d(TAG, "Reply message: " + bundle);
                // 发送回复
                clientMessenger.send(reply);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send reply", e);
            }
        }
    }

    // 主动通知所有客户端 Hook 状态变化
    public void notifyClients(Bundle bundle) {
        for (int i = clients.size() - 1; i >= 0; i--) {
            Messenger client = clients.get(i);
            try {
                Message msg = Message.obtain(null, MSG_NOTIFY_HOOK_STATE);
                msg.setData(bundle);
                client.send(msg); // 尝试向客户端发送消息
            } catch (RemoteException e) {
                clients.remove(i); // 移除无效的客户端
            }
        }
    }

}
