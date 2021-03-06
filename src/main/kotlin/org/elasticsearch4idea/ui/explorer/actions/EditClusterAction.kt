/*
 * Copyright 2020 Anton Shuvaev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.elasticsearch4idea.ui.explorer.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import org.elasticsearch4idea.service.ElasticsearchConfiguration
import org.elasticsearch4idea.service.ElasticsearchManager
import org.elasticsearch4idea.ui.explorer.ElasticsearchExplorer
import org.elasticsearch4idea.ui.explorer.dialogs.ClusterConfigurationDialog
import org.elasticsearch4idea.utils.TaskUtils
import java.awt.Toolkit
import java.awt.event.KeyEvent

class EditClusterAction(private val elasticsearchExplorer: ElasticsearchExplorer) :
    DumbAwareAction("Cluster properties...", "Edit Cluster Configuration", AllIcons.Actions.EditSource) {

    init {
        registerCustomShortcutSet(
            KeyEvent.VK_I,
            Toolkit.getDefaultToolkit().menuShortcutKeyMask,
            elasticsearchExplorer
        )
    }

    override fun actionPerformed(event: AnActionEvent) {
        val cluster = elasticsearchExplorer.getSelectedCluster() ?: return
        val project = event.project!!
        val configuration = project.service<ElasticsearchConfiguration>()
        val currentConfiguration = configuration.getConfiguration(cluster.label)

        val dialog = ClusterConfigurationDialog(
            elasticsearchExplorer,
            project,
            currentConfiguration,
            true
        )
        dialog.title = "Edit Elasticsearch cluster"
        dialog.show()
        if (!dialog.isOK) {
            return
        }
        val clusterConfiguration = dialog.getConfiguration()
        val manager = project.service<ElasticsearchManager>()

        TaskUtils.runBackgroundTask("Getting Elasticsearch cluster info...") {
            manager.prepareChangeCluster(cluster.label, clusterConfiguration)
        }
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = elasticsearchExplorer.getSelectedCluster() != null
    }
}