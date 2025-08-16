package org.koaks.framework.context

object KoaksContext {

    private var scanPackageName: MutableList<String> = mutableListOf()

    init {
        this.scanPackageName.add("org.koaks.framework")
    }

    fun getPackageName(): Array<String> {
        return this.scanPackageName.toTypedArray()
    }

    fun addPackageName(packageName: String) {
        this.scanPackageName.add(packageName)
    }

}