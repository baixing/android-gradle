package com.baixing.plugin

import org.gradle.api.Project
import org.gradle.api.Task

public class BundleExtension {

    /** Package id of bundle */
    //int packageId = 0x2f
    int packageId

    Task aapt
    File symbolFile

    String packageName
    String packagePath

    LinkedHashMap<Integer, Integer> idMaps
    LinkedHashMap<String, String> idStrMaps
    ArrayList retainedTypes
    ArrayList retainedStyleables

    /** List of all resource types */
    ArrayList allTypes

    /** List of all resource styleables */
    ArrayList allStyleables

    public BundleExtension(Project project) {

    }
}
