package com.storyteller_f.dush

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform