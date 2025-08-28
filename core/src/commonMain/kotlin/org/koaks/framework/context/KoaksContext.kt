package org.koaks.framework.context

object KoaksContext {

    private var scanPackageName: MutableList<String> = mutableListOf()

    init {
        this.scanPackageName.add("org.koaks.framework")
    }

    fun getPackageName(): List<String> {
        return this.scanPackageName
    }

    fun addPackageName(packageName: String) {
        this.scanPackageName.add(packageName)
    }

}