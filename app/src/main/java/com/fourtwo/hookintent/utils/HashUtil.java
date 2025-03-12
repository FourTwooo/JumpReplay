package com.fourtwo.hookintent.utils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashUtil {

    /**
     * 使用指定的哈希算法对输入字符串进行加密
     *
     * @param input  要加密的字符串
     * @param algorithm 哈希算法（如 "SHA-256", "SHA-512"）
     * @return 加密后的十六进制字符串
     */
    public static String hash(String input, String algorithm) {
        try {
            // 获取指定算法的 MessageDigest 实例
            MessageDigest digest = MessageDigest.getInstance(algorithm);

            // 将输入字符串转换为字节数组并更新到 MessageDigest
            byte[] hashBytes = digest.digest(input.getBytes());

            // 将字节数组转换为十六进制字符串
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }
}
