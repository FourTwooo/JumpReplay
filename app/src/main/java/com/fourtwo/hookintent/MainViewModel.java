package com.fourtwo.hookintent;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;

public class MainViewModel extends ViewModel {
    private final MutableLiveData<List<ItemData>> intentDataList = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> isHook = new MutableLiveData<>(false);

    public void removeIntentData(int position) {
        List<ItemData> currentData = intentDataList.getValue();
        if (currentData != null && position >= 0 && position < currentData.size()) {
            currentData.remove(position);
            intentDataList.postValue(currentData);
        }
    }
    public LiveData<Boolean> getIsHook() {
        return isHook;
    }

    public void setIsHook(boolean hook) {
        isHook.setValue(hook);
    }

    public LiveData<List<ItemData>> getIntentDataList() {
        return intentDataList;
    }

    public void clearIntentDataList() {
        List<ItemData> emptyList = new ArrayList<>();
        intentDataList.setValue(emptyList);
    }

    public void addIntentData(ItemData data) {
        List<ItemData> currentList = intentDataList.getValue();
        if (currentList != null) {
            currentList.add(data);
            intentDataList.setValue(currentList);
        }
    }
}