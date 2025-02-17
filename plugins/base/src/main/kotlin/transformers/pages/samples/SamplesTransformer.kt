package org.jetbrains.dokka.base.transformers.pages.samples

import com.intellij.psi.PsiElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.dokka.DokkaConfiguration.DokkaSourceSet
import org.jetbrains.dokka.analysis.*
import org.jetbrains.dokka.base.DokkaBase
import org.jetbrains.dokka.base.renderers.sourceSets
import org.jetbrains.dokka.links.DRI
import org.jetbrains.dokka.model.DisplaySourceSet
import org.jetbrains.dokka.model.doc.Sample
import org.jetbrains.dokka.model.properties.PropertyContainer
import org.jetbrains.dokka.pages.*
import org.jetbrains.dokka.plugability.DokkaContext
import org.jetbrains.dokka.plugability.plugin
import org.jetbrains.dokka.plugability.querySingle
import org.jetbrains.dokka.transformers.pages.PageTransformer
import org.jetbrains.kotlin.idea.kdoc.resolveKDocLink
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils

internal const val KOTLIN_PLAYGROUND_SCRIPT = "<script src=\"https://unpkg.com/kotlin-playground@1\"></script>"
abstract class SamplesTransformer(val context: DokkaContext) : PageTransformer {

    abstract fun processBody(psiElement: PsiElement): String
    abstract fun processImports(psiElement: PsiElement): String

    final override fun invoke(input: RootPageNode): RootPageNode =
        /**
         * Run from the thread of [Dispatchers.Default]. It can help to avoid a memory leaks in `ThreadLocal`s (that keep `URLCLassLoader`)
         * since we shut down Dispatchers. Default at the end of each task (see [org.jetbrains.dokka.DokkaConfiguration.finalizeCoroutines]).
         * Currently, all `ThreadLocal`s are in a compiler/IDE codebase.
         */
        runBlocking(Dispatchers.Default) {
            val analysis = SamplesKotlinAnalysis(
                sourceSets = context.configuration.sourceSets,
                logger = context.logger,
                projectKotlinAnalysis = context.plugin<DokkaBase>().querySingle { kotlinAnalysis }
            )
            analysis.use {
                input.transformContentPagesTree { page ->
                    val samples = (page as? WithDocumentables)?.documentables?.flatMap {
                        it.documentation.entries.flatMap { entry ->
                            entry.value.children.filterIsInstance<Sample>().map { entry.key to it }
                        }
                    }

                    samples?.fold(page as ContentPage) { acc, (sampleSourceSet, sample) ->
                        acc.modified(
                            content = acc.content.addSample(page, sampleSourceSet, sample.name, it),
                            embeddedResources = acc.embeddedResources + KOTLIN_PLAYGROUND_SCRIPT
                        )
                    } ?: page
                }
            }
        }

    private fun ContentNode.addSample(
        contentPage: ContentPage,
        sourceSet: DokkaSourceSet,
        fqName: String,
        analysis: KotlinAnalysis
    ): ContentNode {
        val facade = analysis[sourceSet].facade
        val psiElement = fqNameToPsiElement(facade, fqName)
            ?: return this.also { context.logger.warn("Cannot find PsiElement corresponding to $fqName") }
        val imports =
            processImports(psiElement)
        val body = processBody(psiElement)
        val node = contentCode(contentPage.sourceSets(), contentPage.dri, createSampleBody(imports, body), "kotlin")

        return dfs(fqName, node)
    }

    protected open fun createSampleBody(imports: String, body: String) =
        """ |$imports
            |fun main() { 
            |   //sampleStart 
            |   $body 
            |   //sampleEnd
            |}""".trimMargin()

    private fun ContentNode.dfs(fqName: String, node: ContentCodeBlock): ContentNode {
        return when (this) {
            is ContentHeader -> copy(children.map { it.dfs(fqName, node) })
            is ContentDivergentGroup -> @Suppress("UNCHECKED_CAST") copy(children.map {
                it.dfs(fqName, node)
            } as List<ContentDivergentInstance>)
            is ContentDivergentInstance -> copy(
                before.let { it?.dfs(fqName, node) },
                divergent.dfs(fqName, node),
                after.let { it?.dfs(fqName, node) })
            is ContentCodeBlock -> copy(children.map { it.dfs(fqName, node) })
            is ContentCodeInline -> copy(children.map { it.dfs(fqName, node) })
            is ContentDRILink -> copy(children.map { it.dfs(fqName, node) })
            is ContentResolvedLink -> copy(children.map { it.dfs(fqName, node) })
            is ContentEmbeddedResource -> copy(children.map { it.dfs(fqName, node) })
            is ContentTable -> copy(children = children.map { it.dfs(fqName, node) as ContentGroup })
            is ContentList -> copy(children.map { it.dfs(fqName, node) })
            is ContentGroup -> copy(children.map { it.dfs(fqName, node) })
            is PlatformHintedContent -> copy(inner.dfs(fqName, node))
            is ContentText -> if (text == fqName) node else this
            is ContentBreakLine -> this
            else -> this.also { context.logger.error("Could not recognize $this ContentNode in SamplesTransformer") }
        }
    }

    private fun fqNameToPsiElement(resolutionFacade: DokkaResolutionFacade, functionName: String): PsiElement? {
        val packageName = functionName.takeWhile { it != '.' }
        val descriptor = resolutionFacade.resolveSession.getPackageFragment(FqName(packageName))
            ?: return null.also { context.logger.warn("Cannot find descriptor for package $packageName") }
        val symbol = resolveKDocLink(
            BindingContext.EMPTY,
            resolutionFacade,
            descriptor,
            null,
            functionName.split(".")
        ).firstOrNull() ?: return null.also { context.logger.warn("Unresolved function $functionName in @sample") }
        return DescriptorToSourceUtils.descriptorToDeclaration(symbol)
    }

    private fun contentCode(
        sourceSets: Set<DisplaySourceSet>,
        dri: Set<DRI>,
        content: String,
        language: String,
        styles: Set<Style> = emptySet(),
        extra: PropertyContainer<ContentNode> = PropertyContainer.empty()
    ) =
        ContentCodeBlock(
            children = listOf(
                ContentText(
                    text = content,
                    dci = DCI(dri, ContentKind.Sample),
                    sourceSets = sourceSets,
                    style = emptySet(),
                    extra = PropertyContainer.empty()
                )
            ),
            language = language,
            dci = DCI(dri, ContentKind.Sample),
            sourceSets = sourceSets,
            style = styles + ContentStyle.RunnableSample + TextStyle.Monospace,
            extra = extra
        )
}
