package com.fourtwo.hookintent.ui.star;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.fourtwo.hookintent.data.ItemData;

import java.util.ArrayList;
import java.util.List;

public class StarViewModel extends ViewModel {
    private final MutableLiveData<List<ItemData>> intentDataList = new MutableLiveData<>(new ArrayList<>());

    public void removeIntentData(int position) {
        List<ItemData> currentData = intentDataList.getValue();
        if (currentData != null && position >= 0 && position < currentData.size()) {
            currentData.remove(position);
            intentDataList.postValue(currentData);
        }
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