package com.bookmind.core

object TestFixtures {
    val demoBookMarkdown: String by lazy {
        TestFixtures::class.java.getResource("/ogrenmenin_bilimi.md")!!.readText()
    }
}
