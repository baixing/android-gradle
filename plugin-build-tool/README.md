#插件化 plugin

用于编译插件化工程中的插件，打包时去除了工程引用的其他library的资源和class，对插件本身的资源id生成为指定前缀的

## Usage

Apply the plugin in your `build.gradle`:

```groovy
buildscript {
  repositories {
    mavenCentral()
  }
  dependencies {
    classpath 'com.android.tools.build:gradle:2.1.0'
    classpath 'com.baixing.tools.build:android-plugin:1.0'
  }
}

apply plugin: 'com.baixing.gradle.bundle.plugin'
```


apply后，插件会自动查找项目中的android插件，并做资源id的替换等工作

## 设置插件资源前缀

```groovy
bundle {
    packageId = 0x2f
}
```
