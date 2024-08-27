package com.phodal.shire.database

import com.intellij.database.model.DasTable
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.RawDataSource
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.DasUtil
import com.intellij.openapi.project.Project

object DatabaseVariableBuilder {
    fun getDatabases(project: Project): List<DbDataSource> {
        val dbPsiFacade = DbPsiFacade.getInstance(project)
        return dbPsiFacade.dataSources.toList()
    }

    fun getTables(project: Project): List<DasTable> {
        val rawDataSource = retrieveRawDataSources(project) ?: return emptyList()
        val dasTables = rawDataSource.map { rawDataSource ->
            val schemaName = rawDataSource.name.substringBeforeLast('@')
            DasUtil.getTables(rawDataSource).filter { table ->
                table.kind == ObjectKind.TABLE && (table.dasParent?.name == schemaName || isSQLiteTable(rawDataSource, table))
            }
        }.flatten()

        return dasTables
    }

    private fun isSQLiteTable(
        rawDataSource: RawDataSource,
        table: DasTable,
    ) = (rawDataSource.databaseVersion.name == "SQLite" && table.dasParent?.name == "main")

    fun getTableColumns(project: Project, tables: List<String> = emptyList()): List<String> {
        val dasTables = getTables(project)

        if (tables.isEmpty()) {
            return dasTables.map { table ->
                val dasColumns = DasUtil.getColumns(table)
                val columns = dasColumns.map { column ->
                    "${column.name}: ${column.dasType.toDataType()}"
                }.joinToString(", ")

                "TableName: ${table.name}, Columns: $columns"
            }
        }

        return dasTables.mapNotNull { tableName ->
            if (tables.contains(tableName.name)) {
                val dasColumns = DasUtil.getColumns(tableName)
                val columns = dasColumns.map {
                    "${it.name}: ${it.dasType.toDataType()}"
                }.joinToString(", ")

                "TableName: ${tableName.name}, Columns: $columns"
            } else {
                null
            }
        }
    }

    private fun retrieveRawDataSources(project: Project): List<RawDataSource> {
        val dbPsiFacade = DbPsiFacade.getInstance(project)
        val dataSource = dbPsiFacade.dataSources.firstOrNull() ?: return emptyList()
        return dbPsiFacade.getDataSourceManager(dataSource).dataSources
    }
}