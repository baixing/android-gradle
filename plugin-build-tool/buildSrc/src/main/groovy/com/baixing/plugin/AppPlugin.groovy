package com.baixing.plugin

import com.android.build.api.transform.QualifiedContent
import com.baixing.plugin.aapt.Aapt
import com.baixing.plugin.aapt.SymbolParser
import groovy.io.FileType
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task

class AppPlugin implements Plugin<Project> {
    Project project
    BundleExtension bundle

    @Override
    void apply(Project project) {
        this.project = project
        createExtension()
        project.afterEvaluate {
            project.android.applicationVariants.all { variant ->
                configureVariant(variant, variantData)
            }

            project.android.dexOptions {
                preDexLibraries = false // !important, this makes classes.dex splitable
            }
        }
    }

    protected void createExtension() {
        project.extensions.create('bundle', BundleExtension, project)
        bundle = project.bundle
    }

    private void configureVariant(variant, variantData) {
        // 插件的编译不引入instant run
        variantData.getVariantConfiguration().setEnableInstantRunOverride(false)

        def variantName = variant.name.capitalize()

        Task aapt = project.tasks['process' + variantName + 'Resources']
        def newDexTaskName = 'transformClassesWithDexFor' + variantName
        Task dexTask = project.hasProperty(newDexTaskName) ? project.tasks[newDexTaskName] : variant.dex
        Task javac = variant.javaCompile

        bundle.with {
            packageName = variant.applicationId
            packagePath = packageName.replaceAll('\\.', '/')
        }

        aapt.doLast {
            aaptLast(it)
        }

        // 编译时不带入引用的library的so库，也不带入library中的其他资源（如assets，特别是jar包中的assets）
        variantData.outputs.each { vod ->
            vod.packageApplicationTask.conventionMapping.jniFolders = {
                return new HashSet()
            }
            vod.packageApplicationTask.conventionMapping.javaResourceFiles = {
                return new HashSet()
            }
        }

        // 编译时不带入引用的library中的任何资源
        // TODO: 目前插件包中任何直接引用主包中资源都会出现问题（xml或代码中），原因在每次主包编译后其中资源id都可能发生变化，如若打进插件包，会产生兼容问题，后续需要考虑建立资源lib的概念
        variantData.mergeResourcesTask.conventionMapping.inputResourceSets = {
            variantData.variantConfiguration.getResourceSets([], false, false)
        }

        // 插件理论上不应该包含其他library class，故dex打包前去掉所有library
        dexTask.doFirst {
            def inputs = it.consumedInputStreams
            def newInputs = []
            inputs.each { input ->
                if(input.getScopes().contains(QualifiedContent.Scope.PROJECT_LOCAL_DEPS)
                        || input.getScopes().contains(QualifiedContent.Scope.PROJECT)) {
                    newInputs.add(input)
                }
            }
            it.consumedInputStreams = newInputs
        }

        File javacDest
        File bakDir

        // R.xxx只参与编译不参与打包
        javac.doLast {
            File classesDir = it.destinationDir

            javacDest = classesDir
            bakDir = new File(classesDir.getParent(), 'tmp')
            bakDir.mkdir()

            // Delete the original generated R$xx.class or R.class
            moveRFile(classesDir, bakDir)
        }

        variant.assemble.doLast {
            if(null != bakDir && bakDir.exists()) {
                moveRFile(bakDir, javacDest)
                bakDir.deleteDir()
            }
        }

        if(null != bundle.outputDir) {
            def outputFile = new File(bundle.outputDir, 'armeabi/lib-' + project.name + '.so');
            variant.outputs.each { out ->
                out.outputFile = outputFile
            }
        }

    }

    private void moveRFile(File src, File dest) {
        src.eachFileRecurse(FileType.FILES) { f ->
            if (f.name.startsWith('R$') || 'R.class'.equals(f.name)) {
                String path = f.getAbsolutePath().substring(src.getAbsolutePath().length())
                File toFile = new File(dest, path)
                project.ant.move(file: f, tofile: toFile)
            }
        }
    }

    private void aaptLast(aaptTask) {
        // Unpack resources.ap_
        File apFile = aaptTask.packageOutputFile
        File unzipApDir = new File(apFile.parentFile, 'ap_unzip')
        project.copy {
            from project.zipTree(apFile)
            into unzipApDir
        }

        // Modify assets
        prepareSplit(new File(aaptTask.textSymbolOutputDir, 'R.txt'))
        File sourceOutputDir = aaptTask.sourceOutputDir
        File rJavaFile = new File(sourceOutputDir, "${bundle.packagePath}/R.java")
        def rev = project.android.buildToolsRevision
        Aapt aapt = new Aapt(unzipApDir, rJavaFile, null, rev)
        if (bundle.retainedTypes != null) {
            aapt.filterResources(bundle.retainedTypes)

            aapt.filterPackage(bundle.retainedTypes, bundle.packageId, bundle.idMaps,
                    bundle.retainedStyleables)


            String pkg = bundle.packageName
            // Overwrite the aapt-generated R.java with full edition
            aapt.generateRJava(rJavaFile, pkg, bundle.allTypes, bundle.allStyleables)

            // TODO: 把其他包的R文件删掉，这样可以在插件中错误引用R文件时出现编译错误
        }

        // Repack resources.ap_
        project.ant.zip(baseDir: unzipApDir, destFile: apFile)
    }

    private static final int UNSET_TYPEID = 99
    private static final int UNSET_ENTRYID = -1

    /**
     * Prepare retained resource types and resource id maps for package slicing
     */
    protected void prepareSplit(idsFile) {
        if (!idsFile.exists()) return

        def bundleEntries = SymbolParser.getResourceEntries(idsFile)
        def staticIdMaps = [:]
        def staticIdStrMaps = [:]
        def retainedEntries = []
        def retainedStyleables = []

        bundleEntries.each { k, Map be ->
            be._typeId = UNSET_TYPEID // for sort
            be._entryId = UNSET_ENTRYID
            be.isStyleable ? retainedStyleables.add(be) : retainedEntries.add(be)
        }

        if (retainedEntries.size() == 0) {
            bundle.retainedTypes = [] // Doesn't have any resources
            return
        }

        // Prepare types
        def maxPublicTypeId = 0

        // First sort with origin(full) resources order
        retainedEntries.sort { a, b ->
            a.typeId <=> b.typeId ?: a.entryId <=> b.entryId
        }

        // Reassign resource type id (_typeId) and entry id (_entryId)
        def lastEntryIds = [:]
        if (retainedEntries.size() > 0) {
            if (retainedEntries[0].type != 'attr') {
                // reserved for `attr'
                if (maxPublicTypeId == 0) {
                    maxPublicTypeId = 1
                }
            }
            def selfTypes = [:]
            retainedEntries.each { e ->
                // Assign new type with unused type id
                def type = selfTypes[e.type]
                if (type != null) {
                    e._typeId = type.id
                } else {
                    e._typeId = ++maxPublicTypeId
                    selfTypes[e.type] = [id: e._typeId]
                }
                // Simply increase the entry id
                def entryId = lastEntryIds[e.type]
                if (entryId == null) {
                    entryId = 0
                } else {
                    entryId++
                }
                e._entryId = lastEntryIds[e.type] = entryId
            }
        }

        // Resort with reassigned resources order
        retainedEntries.sort { a, b ->
            a._typeId <=> b._typeId ?: a._entryId <=> b._entryId
        }

        // Resort retained resources
        if(0 == bundle.packageId) {
            throw new Exception("empty package id 0x${String.format('%02x', bundle.packageId)} " +
                    "in ${project.name}!\nPlease define packageId " +
                    "in build.gradle (e.g. 'packageId=0x2f'). ")
        }


        def retainedTypes = []
        def pid = (bundle.packageId << 24)
        def currType = null
        retainedEntries.each { e ->
            // Prepare entry id maps for resolving resources.arsc and binary xml files
            if (currType == null || currType.name != e.type) {
                // New type
                currType = [type: e.vtype, name: e.type, id: e.typeId, _id: e._typeId, entries: []]
                retainedTypes.add(currType)
            }
            def newResId = pid | (e._typeId << 16) | e._entryId
            def newResIdStr = "0x${Integer.toHexString(newResId)}"
            staticIdMaps.put(e.id, newResId)
            staticIdStrMaps.put(e.idStr, newResIdStr)

            // Prepare styleable id maps for resolving R.java
            if (retainedStyleables.size() > 0 && e.typeId == 1) {
                retainedStyleables.findAll { it.idStrs != null }.each {
                    // Replace `e.idStr' with `newResIdStr'
                    def index = it.idStrs.indexOf(e.idStr)
                    if (index >= 0) {
                        it.idStrs[index] = newResIdStr
                        it.mapped = true
                    }
                }
            }

            def entry = [name: e.key, id: e.entryId, _id: e._entryId, v: e.id, _v:newResId,
                         vs: e.idStr, _vs: newResIdStr]
            currType.entries.add(entry)
        }

        // Update the id array for styleables
        retainedStyleables.findAll { it.mapped != null }.each {
            it.idStr = "{ ${it.idStrs.join(', ')} }"
            it.idStrs = null
        }

        // Collect all the resources for generating a temporary full edition R.java
        // which required in javac.
        def allTypes = []
        def allStyleables = []
        def addedTypes = [:]
        retainedTypes.each { t ->
            def at = addedTypes[t.name]
            if (at != null) {
                at.entries.addAll(t.entries)
            } else {
                allTypes.add(t)
            }
        }
        allStyleables.addAll(retainedStyleables)

        bundle.idMaps = staticIdMaps
        bundle.idStrMaps = staticIdStrMaps
        bundle.retainedTypes = retainedTypes
        bundle.retainedStyleables = retainedStyleables

        bundle.allTypes = allTypes
        bundle.allStyleables = allStyleables
    }
}