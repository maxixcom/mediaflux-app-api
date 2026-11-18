package ru.mediaflux.app.api.guard

import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.OperationDefinition
import graphql.language.SelectionSet
import graphql.parser.Parser

/** Что запрашивает операция: её тип и корневые поля (без служебных `__typename`, `__schema`). */
data class RootFields(val mutation: Boolean, val fields: Set<String>) {

    companion object {
        /** null — документ не разобрался или операции с таким именем нет: пусть ошибку скажет сам GraphQL. */
        fun of(document: String, operationName: String?): RootFields? {
            val doc: Document = runCatching { Parser.parse(document) }.getOrNull() ?: return null
            val operations = doc.getDefinitionsOfType(OperationDefinition::class.java)
            val op = if (operationName.isNullOrBlank()) operations.singleOrNull() else operations.firstOrNull { it.name == operationName }
            op ?: return null
            val fragments = doc.getDefinitionsOfType(FragmentDefinition::class.java).associateBy { it.name }
            val fields = mutableSetOf<String>()
            collect(op.selectionSet, fragments, fields, depth = 0)
            return RootFields(op.operation == OperationDefinition.Operation.MUTATION, fields.filterNot { it.startsWith("__") }.toSet())
        }

        private fun collect(set: SelectionSet?, fragments: Map<String, FragmentDefinition>, out: MutableSet<String>, depth: Int) {
            if (set == null || depth > 5) return
            set.selections.forEach { sel ->
                when (sel) {
                    is Field -> out += sel.name
                    is InlineFragment -> collect(sel.selectionSet, fragments, out, depth + 1)
                    is FragmentSpread -> collect(fragments[sel.name]?.selectionSet, fragments, out, depth + 1)
                }
            }
        }
    }
}
