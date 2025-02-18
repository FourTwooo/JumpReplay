package com.fourtwo.hookintent.viewmodel;


import androidx.lifecycle.ViewModel;


public class MeViewModel extends ViewModel {
    private String responseData;

    public String getResponseData() {
        return responseData;
    }

    public void setResponseData(String responseData) {
        this.responseData = responseData;
    }
}