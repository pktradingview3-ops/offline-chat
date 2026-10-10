package com.offlinechat.identity

import java.security.SecureRandom

/**
 * Random, anonymous, non-traceable user ID — web app ke identity.ts jaisa hi format,
 * taaki logs/UI me dono platforms consistent dikhein. Koi personal info kabhi encode nahi hoti.
 */
fun generateAnonId(): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    val random = SecureRandom()
    val suffix = (1..6).map { chars[random.nextInt(chars.length)] }.joinToString("")
    return "anon-$suffix"
}
