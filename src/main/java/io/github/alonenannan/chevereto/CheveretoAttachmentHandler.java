package io.github.alonenannan.chevereto;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static io.github.alonenannan.chevereto.CheveretoUtils.validateApiUrl;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.Extension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import run.halo.app.core.attachment.ThumbnailSize;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Constant;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.attachment.endpoint.AttachmentHandler;
import run.halo.app.extension.ConfigMap;

@Slf4j
@Extension
@Component
public class CheveretoAttachmentHandler implements AttachmentHandler {

    private static final String IMAGE_ID_ANNO_KEY = "chevereto.halo.run/image-id";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final WebClient webClient = WebClient.builder().build();

    static {
        MAPPER.getFactory().setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxStringLength(5_000_000)
                .maxNestingDepth(1000)
                .maxNumberLength(2000)
                .build());
    }

    public boolean shouldHandle(Policy policy) {
        if (policy == null || policy.getSpec() == null) {
            return false;
        }
        return "chevereto".equals(policy.getSpec().getTemplateName());
    }

    @Override
    public Mono<Attachment> upload(UploadContext context) {
        if (!shouldHandle(context.policy())) {
            return Mono.empty();
        }
        String filename = context.file().filename();
        log.info("[chevereto] uploading: {}", filename);

        ConfigMap configMap = context.configMap();

        return DataBufferUtils.join(context.file().content())
            .flatMap(dataBuffer -> {
                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                DataBufferUtils.release(dataBuffer);
                return doUpload(bytes, filename, configMap)
                    .map(result -> {
                        var attachment = toAttachment(result, filename, (long) bytes.length);
                        log.info("[chevereto] uploaded: {} → {}", filename, result.getUrl());
                        return attachment;
                    });
            })
            .doOnError(e -> log.error("[chevereto] upload failed: {}", e.getMessage(), e));
    }

    @Override
    public Mono<Attachment> delete(DeleteContext context) {
        if (!shouldHandle(context.policy())) {
            return Mono.empty();
        }
        var attachment = context.attachment();
        var idEncoded = readImageId(attachment);
        if (idEncoded == null || idEncoded.isEmpty()) {
            log.warn("[chevereto] delete skipped, no image id annotation for {}",
                attachment.getMetadata() != null ? attachment.getMetadata().getName() : "unknown");
            return Mono.just(attachment);
        }
        log.info("[chevereto] deleting remote image: {}", idEncoded);
        return doDelete(context.configMap(), idEncoded)
            .doOnSuccess(v -> log.info("[chevereto] remote image deleted: {}", idEncoded))
            .doOnError(e -> log.error("[chevereto] remote delete failed: {}", e.getMessage()))
            .onErrorResume(e -> Mono.empty())
            .then(Mono.just(attachment));
    }

    /**
     * 重写 getPermalink 以返回外部图床的完整 URL。
     * Halo 处理用户头像时会调用此方法获取附件访问链接，
     * 如果不重写，Halo 的默认逻辑无法解析外部 URL，导致头像无法显示。
     */
    @Override
    public Mono<URI> getPermalink(Attachment attachment, Policy policy, ConfigMap configMap) {
        if (!shouldHandle(policy)) {
            return Mono.empty();
        }
        var permalink = attachment.getStatus() != null
            ? attachment.getStatus().getPermalink() : null;
        if (permalink != null && !permalink.isEmpty()) {
            return Mono.just(URI.create(permalink));
        }
        var annotations = attachment.getMetadata() != null
            ? attachment.getMetadata().getAnnotations() : null;
        if (annotations != null) {
            var externalLink = annotations.get(Constant.EXTERNAL_LINK_ANNO_KEY);
            if (externalLink != null && !externalLink.isEmpty()) {
                return Mono.just(URI.create(externalLink));
            }
        }
        log.warn("[chevereto] getPermalink failed for attachment {}",
            attachment.getMetadata() != null ? attachment.getMetadata().getName() : "unknown");
        return Mono.empty();
    }

    /**
     * 明确返回空缩略图 map，告诉 Halo 本插件不生成缩略图。
     */
    @Override
    public Mono<Map<ThumbnailSize, URI>> getThumbnailLinks(Attachment attachment,
                                                           Policy policy,
                                                           ConfigMap configMap) {
        if (!shouldHandle(policy)) {
            return Mono.empty();
        }
        return Mono.just(Map.of());
    }

    @Override
    public Mono<URI> getSharedURL(Attachment attachment, Policy policy,
                                  ConfigMap configMap, Duration ttl) {
        return getPermalink(attachment, policy, configMap);
    }

    // ─── upload logic ────────────────────────────────────────────────

    private Mono<UploadResult> doUpload(byte[] fileBytes, String filename, ConfigMap cm) {
        var cfg = readConfig(cm);
        String uploadUrl = cfg.apiUrl + "/api/1/upload";

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("source", new ByteArrayResource(fileBytes) {
            @Override public String getFilename() { return filename; }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);
        bodyBuilder.part("key", cfg.apiKey);
        bodyBuilder.part("format", "json");
        if (cfg.albumId != null && !cfg.albumId.isBlank()) {
            bodyBuilder.part("album_id", cfg.albumId);
        }

        return webClient.post()
            .uri(uploadUrl)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
            .retrieve()
            .bodyToMono(String.class)
            .map(responseBody -> {
                if (responseBody == null) {
                    throw new RuntimeException("Chevereto 返回空响应");
                }
                return parseResponse(responseBody, cfg.apiUrl);
            });
    }

    private UploadResult parseResponse(String json, String apiBaseUrl) {
        try {
            JsonNode root = MAPPER.readTree(json);
            int statusCode = root.path("status_code").asInt(-1);
            if (statusCode != 200) {
                String msg = root.path("error").path("message").asText("未知错误");
                throw new RuntimeException("Chevereto 错误 status_code=" + statusCode + ": " + msg);
            }

            JsonNode image = root.get("image");
            if (image == null || !image.isObject()) {
                log.error("Chevereto 响应缺少 image 字段, 响应前500字符: {}",
                    json.length() > 500 ? json.substring(0, 500) + "..." : json);
                throw new RuntimeException("Chevereto 响应缺少 image 字段");
            }

            String url = image.path("url").asText(null);
            if (url == null || url.isEmpty()) {
                throw new RuntimeException("Chevereto 未返回图片 URL（图片可能未审核）");
            }
            // 相对路径 → 拼接完整 URL
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = apiBaseUrl + (url.startsWith("/") ? url : "/" + url);
            }

            var r = new UploadResult();
            r.setUrl(url);
            r.setName(image.has("filename") ? image.get("filename").asText() : null);
            r.setMimeType(image.has("mime") ? image.get("mime").asText() : null);
            r.setSize(image.has("size") ? image.get("size").asLong() : null);
            r.setIdEncoded(image.has("id_encoded") ? image.get("id_encoded").asText() : null);
            return r;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析响应失败: " + e.getMessage(), e);
        }
    }

    // ─── delete logic ────────────────────────────────────────────────

    private String readImageId(Attachment attachment) {
        var annotations = attachment.getMetadata() != null
            ? attachment.getMetadata().getAnnotations() : null;
        if (annotations == null) {
            return null;
        }
        return annotations.get(IMAGE_ID_ANNO_KEY);
    }

    private Mono<Void> doDelete(ConfigMap cm, String idEncoded) {
        var cfg = readConfig(cm);
        return webClient.post()
            .uri(cfg.apiUrl + "/delete")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromFormData("key", cfg.apiKey).with("id", idEncoded))
            .retrieve()
            .bodyToMono(String.class)
            .flatMap(body -> {
                try {
                    JsonNode root = MAPPER.readTree(body);
                    int statusCode = root.path("status_code").asInt(-1);
                    if (statusCode != 200) {
                        String msg = root.path("error").path("message").asText("未知错误");
                        return Mono.error(new RuntimeException("Chevereto 删除失败: " + msg));
                    }
                    return Mono.empty();
                } catch (Exception e) {
                    return Mono.error(new RuntimeException("解析删除响应失败: " + e.getMessage(), e));
                }
            });
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private Attachment toAttachment(UploadResult r, String filename, Long size) {
        var a = new Attachment();
        var metadata = new run.halo.app.extension.Metadata();
        metadata.setName(UUID.randomUUID().toString());

        var annotations = new HashMap<String, String>();
        annotations.put(Constant.EXTERNAL_LINK_ANNO_KEY, r.getUrl());
        if (r.getIdEncoded() != null && !r.getIdEncoded().isEmpty()) {
            annotations.put(IMAGE_ID_ANNO_KEY, r.getIdEncoded());
        }
        metadata.setAnnotations(annotations);

        a.setMetadata(metadata);
        var spec = new Attachment.AttachmentSpec();
        spec.setDisplayName(r.getName() != null ? r.getName() : filename);
        spec.setMediaType(r.getMimeType() != null ? r.getMimeType() : "application/octet-stream");
        spec.setSize(r.getSize() != null ? r.getSize() : size);
        a.setSpec(spec);

        var st = new Attachment.AttachmentStatus();
        st.setPermalink(r.getUrl());
        a.setStatus(st);
        return a;
    }

    private CheveretoCfg readConfig(ConfigMap cm) {
        var data = cm.getData();
        String raw = data.get("default");
        if (raw == null || raw.isBlank()) {
            throw new RuntimeException("存储策略配置为空，请检查是否填写了 API 地址和 API Key");
        }
        try {
            JsonNode root = MAPPER.readTree(raw);
            String apiUrl = nodeText(root, "apiUrl");
            String apiKey = nodeText(root, "apiKey");
            String albumId = nodeText(root, "albumId", "");
            if (apiUrl == null || apiUrl.isBlank()) throw new RuntimeException("未配置 API 地址");
            if (apiKey == null || apiKey.isBlank()) throw new RuntimeException("未配置 API Key");
            apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
            apiUrl = validateApiUrl(apiUrl);
            return new CheveretoCfg(apiUrl, apiKey, albumId);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析存储策略配置失败: " + e.getMessage(), e);
        }
    }

    private static String nodeText(JsonNode node, String key, String defaultVal) {
        return node.has(key) && !node.get(key).isNull() ? node.get(key).asText() : defaultVal;
    }

    private static String nodeText(JsonNode node, String key) {
        return nodeText(node, key, null);
    }

    // ─── inner types ─────────────────────────────────────────────────

    @Data
    private static class UploadResult {
        private String url, name, mimeType, idEncoded;
        private Long size;
    }

    private record CheveretoCfg(String apiUrl, String apiKey, String albumId) {}
}
