## Mediainfo Android / libmediainfo
Mediainfo是一个广为人知的开源多媒体文件信息查看工具，开发者MEDIAAREA提供了多个平台的可执行文件及源代码。  
然而，将Mediainfo应用到自己的项目中，仍然具有较大难度，第一步编译CMake就很容易出现问题。
如果把调用CMake改为使用预编译的so文件，事情就简单多了！
所以我直接从官方编译的Android版mediainfo中提取了libmediainfo.so文件，把官方发布的Android平台需要CMake的代码，改为了使用预编译so文件，从而降低了编译难度。
如果你同样有在Android平台使用libmediainfo的需求，可以按照如下方法使用本项目：

1. 下载本项目并解压

2. 复制`\Android\app\libs`到你的项目中的app目录

3. 复制`Android\app\src\main\java\net\mediaarea\mediainfo`目录中`core.kt`和`MediaInfo.kt`两个文件到你的项目中

4. 使用如下代码，得到text格式的mediainfo report。更多方法参考libmediainfo原项目示例代码。

   ```kotlin
    Core.INSTANCE.creatReport(path_root);
   ```

5. build.gradle的Android小节增加如下内容，应用so文件

   ```
       sourceSets {
           main {
               jniLibs.srcDirs = ['libs']
           }
       }
   ```

## 相关路径

Mediainfo Android版本下载

​	https://mediaarea.net/en/MediaInfo/Download/Android

Mediainfo 源代码下载：

​	https://mediaarea.net/en/MediaInfo/Download/Source

Mediainfo Android版本代码

​	mediainfo_AllInclusive\MediaInfo\Source\GUI\Android