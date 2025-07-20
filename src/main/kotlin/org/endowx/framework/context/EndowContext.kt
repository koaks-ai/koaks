package org.endowx.framework.context

object EndowContext {

    private var scanPackageName: MutableList<String> = mutableListOf()

    init {
        this.scanPackageName.add("org.endowx.framework")
    }

    fun getPackageName(): Array<String> {
        return this.scanPackageName.toTypedArray()
    }

    fun addPackageName(packageName: String) {
        this.scanPackageName.add(packageName)
    }

}