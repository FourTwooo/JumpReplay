package com.fourtwo.hookintent.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class MainViewModel extends ViewModel {
    private final MutableLiveData<Boolean> isHook = new MutableLiveData<>(false);
    public LiveData<Boolean> getIsHook() { return isHook; }
    public void setIsHook(boolean hook) { isHook.setValue(hook); }
}
