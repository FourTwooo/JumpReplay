package com.fourtwo.hookintent.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class MainViewModel extends ViewModel {
    public LiveData<Boolean> getIsHook() { return com.fourtwo.hookintent.manager.HookStatusManager.state(); }
    public void setIsHook(boolean hook) { com.fourtwo.hookintent.manager.HookStatusManager.setHook(hook); }
}
