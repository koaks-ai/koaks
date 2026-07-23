package org.koaks.framework.skill

import okio.FileSystem

internal actual suspend fun defaultSkillFileSystem(): SkillFileSystem? = OkioSkillFileSystem(FileSystem.SYSTEM)
