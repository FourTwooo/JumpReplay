package com.fourtwo.hookintent.analysis;

import android.annotation.SuppressLint;
import android.net.Uri;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UriData {

    public static Map<String, Object> convertUriToMap(Uri uri) {
        Map<String, Object> uriMap = new HashMap<>();

        if (uri != null) {
            uriMap.put("scheme", uri.getScheme());
            uriMap.put("schemeSpecificPart", uri.getSchemeSpecificPart());
            uriMap.put("authority", uri.getAuthority());
            uriMap.put("userInfo", uri.getUserInfo());
            uriMap.put("host", uri.getHost());
            uriMap.put("port", uri.getPort());
            uriMap.put("path", uri.getPath());
            uriMap.put("query", uri.getQuery());
            uriMap.put("fragment", uri.getFragment());

            // Add path segments
            List<String> pathSegments = uri.getPathSegments();
            if (!pathSegments.isEmpty()) {
                uriMap.put("pathSegments", pathSegments);
            }

            // Add last path segment
            String lastPathSegment = uri.getLastPathSegment();
            if (lastPathSegment != null) {
                uriMap.put("lastPathSegment", lastPathSegment);
            }
        }

        return uriMap;
    }

    @SuppressLint("SimpleDateFormat")
    public static Map<String, Object> GetMap(String scheme_raw_url){
        Map<String, Object> uriMap = new HashMap<>();
        uriMap.put("time", new SimpleDateFormat("yyyy-MM-dd hh:mm:ss a").format(Calendar.getInstance().getTime()));
        uriMap.put("scheme_raw_url", scheme_raw_url);
        return uriMap;
    }
}
