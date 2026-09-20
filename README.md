# Chevereto 图床插件（Halo 2.x）

将自部署的 [Chevereto](https://chevereto.com/) 图床作为 Halo 的附件存储后端。在 Halo 后台上传图片后，文件会直传到你的 Chevereto 站点，并返回 Chevereto 的外链地址；在 Halo 中删除附件时，会**联动删除** Chevereto 上的原图。

- 插件 ID：`chevereto`
- 适用版本：Halo `>= 2.25.0`
- 构建环境：JDK 21 / Gradle 8.x

---

## 关于本项目的开发方式（AI 辅助开发说明）

本项目**由 DeepSeek 与 HY4 两个大模型辅助开发完成**，人类负责需求提出、方案拍板、联调验证与最终代码审查。

具体分工如下：

| 环节 | 主要参与 | 说明 |
| --- | --- | --- |
| 需求拆解与方案评估 | 人工 + DeepSeek | 确认走「Chevereto 自定义路由 + Halo AttachmentHandler」而非改动 Chevereto 源码 |
| Chevereto 源码扩展点定位 | DeepSeek | 通读 `app/src/Legacy/G/Handler.php`，定位官方预留的 `routes/overrides/` 覆盖机制 |
| Chevereto 侧 `delete.php` 编写 | DeepSeek | 基于 V1 API Key 鉴权 + `Image::delete()`，实现零侵入的删除路由 |
| Halo 插件骨架与上传链路 | DeepSeek | 参考 `oneimg-halo-plugin`，实现 `AttachmentHandler` 的 `upload()` |
| 联动删除链路（annotation + DeleteContext） | HY4 | 反编译确认 `DeleteContext` 为内部接口，补 `IMAGE_ID_ANNO_KEY` 与 `doDelete()` |
| 安全问题加固（SSRF、隐私泄露） | HY4 | 增加 `validateApiUrl()` 公网 HTTPS 校验；随机图 API 的 debug 改为环境变量开关 |
| 构建环境问题排查 | 人工 + DeepSeek | Gradle 发行版走腾讯云镜像、Maven 依赖走本地代理 `127.0.0.1:7890` |
| 文档整理 | HY4 | 本文档 |

> 说明：插件逻辑、接口字段与权限判断均经过真实环境联调验证（上传成功、相册归属、删除返回 `affected=1`）。AI 生成的代码已由人工逐行审查，但使用仍需自负风险，请先在测试站点验证。

---

## 功能特性

- **上传**：Halo 附件 → Chevereto `POST /api/1/upload`，支持指定相册
- **外链回写**：把 Chevereto 返回的 `image.url` 写入附件的 `permalink` 与 `EXTERNAL_LINK_ANNO_KEY`，前台直接引用外链
- **联动删除**：Halo 删除附件 → 调用 Chevereto `POST /delete` 删除远端原图（失败仅记日志，不阻塞 Halo 侧的删除流程）
- **头像可用**：重写 `getPermalink()`，保证用户头像等走外部链接的场景正常显示
- **无缩略图**：`getThumbnailLinks()` 返回空 Map，交给 Chevereto 自身处理尺寸
- **SSRF 防护**：API 地址强制 HTTPS 且拒绝内网/回环地址

---

## 前置条件

1. Halo `>= 2.25.0`
2. 一个可访问的 Chevereto V4 站点（必须 HTTPS 公网域名）
3. 在 Chevereto 后台生成的 **API V1 Key**（个人 Key 或 Guest Key 均可）
4. 已完成下节「Chevereto 侧部署 delete.php」（否则联动删除不可用，上传不受影响）

---

## 一、Chevereto 侧部署 `delete.php`

> 这是**联动删除**的服务端依赖。文件位于本仓库 `chevereto-overrides/delete.php`。
> 采用 Chevereto 官方预留的 `routes/overrides/` 机制，**不修改任何原有代码**。

### 部署步骤

```bash
# 假设 Chevereto 容器内站点根为 /var/www/html
cp chevereto-overrides/delete.php  <站点根目录>/app/legacy/routes/overrides/delete.php
```

Docker 部署时建议直接挂载：

```yaml
volumes:
  - ./chevereto-overrides/delete.php:/var/www/html/app/legacy/routes/overrides/delete.php:ro
```

### 接口说明

| 项目 | 值 |
| --- | --- |
| 路径 | `POST /delete` |
| Content-Type | `application/x-www-form-urlencoded` |
| 参数 `key` | Chevereto API V1 Key |
| 参数 `id` | 图片的**编码 ID**（如 `fDR`、`Cn`，即上传响应中的 `image.id_encoded`） |

权限规则：普通用户 Key 只能删除**自己**的图片；管理员 Key 可删除任意图片。非本人资源返回 `Invalid content owner request`。

成功响应：

```json
{
  "status_code": 200,
  "success": {
    "message": "Image deleted",
    "code": 200,
    "affected": 1
  }
}
```

失败响应：

```json
{
  "status_code": 400,
  "error": {
    "message": "Invalid content owner request",
    "code": 114
  }
}
```

调试验证（命令行）：

```bash
curl -X POST "https://img.example.com/delete" \
  -d "key=你的API_KEY" \
  -d "id=fDR"
```

---

## 二、安装插件

### 方式一：从 Release 下载（推荐）

1. 到 [Releases](https://github.com/157888390/chevereto-halo-plugin/releases) 下载 `plugin-chevereto-1.0.0.jar`
2. Halo 后台 → **插件** → **安装** → 上传 JAR

### 方式二：本地构建

```bash
./gradlew build
# 产物：build/libs/plugin-chevereto-1.0.0.jar
```

Windows：

```bat
gradlew.bat build
```

> 若 Maven 依赖下载慢，可在 `gradle.properties`（**该文件已被 .gitignore 忽略**）中配置本地代理：
> ```
> systemProp.http.proxyHost=127.0.0.1
> systemProp.http.proxyPort=7890
> systemProp.https.proxyHost=127.0.0.1
> systemProp.https.proxyPort=7890
> ```

---

## 三、配置存储策略

1. Halo 后台 → **附件** → **存储策略** → **新增**
2. 模板选择 **Chevereto 图床**
3. 填写表单：

| 字段 | 说明 | 示例 |
| --- | --- | --- |
| `apiUrl` | Chevereto 站点地址，**必须 HTTPS** | `https://img.example.com` |
| `apiKey` | Chevereto 后台生成的 API V1 Key | `****` |
| `albumId` | 可选，目标相册的**编码 ID** | `vJ` |

4. 保存后回到 **附件** → 把该策略设为默认（或上传时手动选择）

### ⚠️ 相册 ID 不是数字

Chevereto 的相册/图片使用 **编码 ID**，不是数据库自增 ID。获取方式：打开相册页面，URL 形如 `https://img.example.com/album/tu.vJ`，其中 **`vJ`** 就是编码 ID。填 `2` 这类数字不会生效。

---

## 四、使用

- 后台 **附件** 页面上传、编辑器插入图片、设置头像，均会走 Chevereto
- 在 Halo 中删除附件 → 日志出现 `[chevereto] remote image deleted: <id>` 表示远端同步删除成功
- 日志前缀统一为 `[chevereto]`，便于排查

---

## 工作原理

```
Halo 上传
  └─> CheveretoAttachmentHandler.upload()
        ├─ 读取 ConfigMap（apiUrl / apiKey / albumId）
        ├─ validateApiUrl() 校验 HTTPS + 公网
        ├─ POST {apiUrl}/api/1/upload  (multipart: source/key/format/album_id)
        └─ 解析 image.url → 写 permalink
           解析 image.id_encoded → 写 annotation "chevereto.halo.run/image-id"

Halo 删除
  └─> CheveretoAttachmentHandler.delete()
        ├─ 从 annotation 读出 id_encoded
        └─ POST {apiUrl}/delete  (form: key/id)
             └─> Chevereto app/legacy/routes/overrides/delete.php
                   ├─ ApiKey::verify() 鉴权
                   ├─ decodeID() 解码
                   ├─ 归属校验（管理员可越权）
                   └─ Image::delete()
```

关键点：Chevereto 侧 `Handler::getRouteFn()` 会**优先**加载 `app/legacy/routes/overrides/<name>.php`，因此新增 `delete.php` 即可挂载 `/delete` 路由，无需改动 Chevereto 源码。

---

## 目录结构

```
chevereto-halo-plugin/
├── build.gradle
├── settings.gradle                     # rootProject.name = 'plugin-chevereto'
├── gradle/wrapper/
├── chevereto-overrides/
│   └── delete.php                      # Chevereto 侧删除路由（需部署到站点）
├── src/main/java/io/github/alonenannan/chevereto/
│   ├── CheveretoPlugin.java
│   ├── CheveretoAttachmentHandler.java # 上传 / 删除 / 外链 / 缩略图
│   └── CheveretoUtils.java             # API 地址 SSRF 校验
└── src/main/resources/
    ├── plugin.yaml
    ├── logo.png
    └── extensions/policy-template.yaml  # 存储策略表单
```

---

## 常见问题

**Q：上传成功但相册 ID 没生效？**
A：请填**编码 ID**（如 `vJ`），不是数据库数字 ID。另外 Guest API Key 可能无权写入指定相册，换成用户个人 Key 试试。

**Q：提示「Chevereto 未返回图片 URL（图片可能未审核）」？**
A：Chevereto 开启了「上传需审核」时，未审核图片的 `url` 为空。关闭审核或先审核后再上传。

**Q：配置好了但报「API 地址必须使用 HTTPS」/「不允许使用内网地址」？**
A：`CheveretoUtils.validateApiUrl()` 强制要求 HTTPS + 公网解析，防止 SSRF。局域网自签证书的站点需要自行放宽该校验重新构建。

**Q：删除附件后 Chevereto 上的图还在？**
A：检查三件事——① `delete.php` 是否已放到容器的 `app/legacy/routes/overrides/`；② 该插件版本是否支持删除（1.0.0 起）；③ 查看日志 `[chevereto] remote delete failed: ...`。注意历史附件（1.0.0 之前上传的）没有 `image-id` annotation，无法联动删除，只会打 `[chevereto] delete skipped`。

**Q：私有相册的图能正常显示吗？**
A：能。Chevereto 的相册隐私只在**查看页面**做校验，图片直链本身没有访问控制，所以外链可正常加载。

---

## 许可证

[GPL-3.0](./LICENSE)

Chevereto 为商业软件，本插件仅调用其官方 API，不包含 Chevereto 源码。
