/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.datastore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** The initial placeholder does not represent the user's saved switches. */
internal suspend fun Flow<Settings>.firstLoadedSettings(): Settings = first { !it.init }
