package com.fourtwo.hookintent.ui.detail;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.fourtwo.hookintent.ItemData;

public class DetailViewModel extends ViewModel {
    private final MutableLiveData<ItemData> itemData = new MutableLiveData<>();

    public LiveData<ItemData> getItemData() {
        return itemData;
    }

    public void setItemData(ItemData data) {
        itemData.setValue(data);
    }
}