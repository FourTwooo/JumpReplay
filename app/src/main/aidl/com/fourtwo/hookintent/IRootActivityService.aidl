// IRootActivityService.aidl
package com.fourtwo.hookintent;

// Declare any non-default types here with import statements

interface IRootActivityService {
    int startActivityAsRoot(in Intent intent, int userId);
}