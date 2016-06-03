# android-gradle

放 android 团队用的 gradle 插件

## gradle 调试方法

### 1. 开一个remote的debug config

run -> Edit Configurations -> + -> Remote

### 2. 开启debug模式

```shell
export GRADLE_OPTS="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"
```

### 3. start some task

```shell
./gradlew assembleRelease -Dorg.gradle.daemon=false
```

### 4. attach

点一下ide上的小虫子吧，和debug程序一样

### 5. 关闭debug模式

```shell

unset GRADLE_OPTS
```