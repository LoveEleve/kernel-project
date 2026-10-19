# D4: SpringEncoder / SpringDecoder 深度源码分析

> 源码版本: Spring Cloud OpenFeign 4.1.3（Spring Cloud 2023.0.3，xhs-project 口径）
> 源码路径:
> - `spring-cloud-openfeign-core/.../support/SpringEncoder.java` (273 行)
> - `spring-cloud-openfeign-core/.../support/SpringDecoder.java` (122 行)
> - `spring-cloud-openfeign-core/.../support/FeignEncoderProperties.java` (44 行)
> 测试文件:
> - `SpringEncoderTests.java` (354 行)
> - `SpringDecoderTests.java` (56 行，位于 `org.springframework.cloud.openfeign` 包)

## 一、类定位与职责

### 1.1 一句话定位

`SpringEncoder` 和 `SpringDecoder` 是 Feign 与 Spring `HttpMessageConverter` 体系之间的**适配器**--它们将 Feign 的序列化/反序列化委托给 Spring 的转换器链，使得 Feign 可以直接处理 Java 对象而非原始字节。

### 1.2 SpringEncoder 类声明

```java
@SuppressWarnings("rawtypes")
public class SpringEncoder implements Encoder {

    private static final Log log = LogFactory.getLog(SpringEncoder.class);

    private final SpringFormEncoder springFormEncoder;                        // 表单/multipart 编码器
    private final ObjectFactory<HttpMessageConverters> messageConverters;     // 转换器来源（Spring Boot）
    private final FeignEncoderProperties encoderProperties;                   // 编码器配置
    private final ObjectProvider<HttpMessageConverterCustomizer> customizers; // 转换器自定义器
    private List<HttpMessageConverter<?>> converters;                         // 首次 encode 时初始化
}
```

**五个依赖/字段**:

| 依赖 | 类型 | 来源 | 作用 |
|------|------|------|------|
| `springFormEncoder` | `SpringFormEncoder` | 构造函数传入 | 处理 `multipart/form-data` 和 `application/x-www-form-urlencoded` |
| `messageConverters` | `ObjectFactory<HttpMessageConverters>` | `FeignClientsConfiguration` 子容器 | 提供 `HttpMessageConverter` 列表（延迟 `getObject()`） |
| `encoderProperties` | `FeignEncoderProperties` | `spring.cloud.openfeign.encoder` 配置 | 控制 charset 行为 |
| `customizers` | `ObjectProvider<HttpMessageConverterCustomizer>` | 子容器（可选） | 对转换器列表做后置定制 |
| `converters` | `List<HttpMessageConverter<?>>` | 首次 encode 时赋值 | 实际遍历的转换器列表 |

### 1.3 SpringDecoder 类声明

```java
public class SpringDecoder implements Decoder {

    private final ObjectFactory<HttpMessageConverters> messageConverters;

    private final ObjectProvider<HttpMessageConverterCustomizer> customizers;
}
```

Decoder 比 Encoder 简单得多: 仅需 `messageConverters` + `customizers`，无需表单编码器或属性配置。

### 1.4 Feign 原生 vs Spring Cloud 对比

| 维度 | Feign 原生 | Spring Cloud (SpringEncoder/Decoder) |
|------|-----------|--------------------------------------|
| JSON | `JacksonEncoder` / `JacksonDecoder` | `MappingJackson2HttpMessageConverter` |
| XML | `JaxbEncoder` / `JaxbDecoder` | `Jaxb2RootElementHttpMessageConverter` |
| 表单 | `FormEncoder` | `SpringFormEncoder` + `FormHttpMessageConverter` |
| 自定义 | 需实现 `Encoder`/`Decoder` 接口 | 注册 `HttpMessageConverter` Bean 即可 |
| 类型支持 | 需每个 Encoder/Decoder 独立处理 | Spring 的 `HttpMessageConverter` 链统一处理 |
| 响应包装 | 不支持 | `ResponseEntityDecoder` 包装 `SpringDecoder` |

### 1.5 FeignEncoderProperties

```java
@ConfigurationProperties("spring.cloud.openfeign.encoder")
public class FeignEncoderProperties {

    /**
     * Indicates whether the charset should be derived from the Content-Type header.
     */
    private boolean charsetFromContentType = false;

    public boolean isCharsetFromContentType() { return charsetFromContentType; }
    public void setCharsetFromContentType(boolean charsetFromContentType) { ... }
}
```

仅一个属性 `charsetFromContentType`（默认 `false`）:
- `false`: 所有文本请求体使用 `UTF-8` charset（除二进制和 protobuf）
- `true`: 从 `Content-Type` header 的 charset 参数获取（如 `text/plain; charset=ISO-8859-1`）

### 1.6 转换器列表的初始化（initConvertersIfRequired）

```java
private void initConvertersIfRequired() {
    if (converters == null) {
        converters = messageConverters.getObject().getConverters();
        customizers.forEach(customizer -> customizer.accept(converters));
    }
}
```

**首次 encode 时初始化**（简单判空，源码未加锁）:
1. `messageConverters.getObject().getConverters()` -- Spring Boot 的 `HttpMessageConverters`（Jackson、String、ByteArray、Resource、Form 等）
2. `customizers` -- 每个 `HttpMessageConverterCustomizer` 对列表做后置定制（`accept`）

> 版本差异: 4.3.2 引入了独立的 `FeignHttpMessageConverters`（volatile + synchronized 双重检查锁，并用 `HttpMessageConverters.forClient().registerDefaults()` 重建默认转换器）；4.1.3 是上面的简单初始化。

## 二、SpringEncoder.encode() 逐行分析

### 2.1 完整源码

```java
@Override
public void encode(Object requestBody, Type bodyType, RequestTemplate request) throws EncodeException {
    if (requestBody != null) {
        // 1. 从请求中获取 Content-Type
        Collection<String> contentTypes = request.headers().get(HttpEncoding.CONTENT_TYPE);

        MediaType requestContentType = null;
        if (contentTypes != null && !contentTypes.isEmpty()) {
            String type = contentTypes.iterator().next();
            requestContentType = MediaType.valueOf(type);
        }

        // 2. 表单类型 -> 委托给 SpringFormEncoder
        if (isFormRelatedContentType(requestContentType)) {
            springFormEncoder.encode(requestBody, bodyType, request);
            return;
        }
        else {
            // 3. MultipartFile 警告
            if (bodyType == MultipartFile.class) {
                log.warn("For MultipartFile to be handled correctly, the 'consumes' parameter of @RequestMapping "
                        + "should be specified as MediaType.MULTIPART_FORM_DATA_VALUE");
            }
        }
        // 4. 其他类型 -> 遍历 HttpMessageConverter
        encodeWithMessageConverter(requestBody, bodyType, request, requestContentType);
    }
}
```

### 2.2 分支路由

```
encode(requestBody, bodyType, request)
  │
  ├── requestBody == null -> 跳过(不处理)
  │
  ├── Content-Type = multipart/form-data | multipart/mixed | multipart/related | application/x-www-form-urlencoded
  │     └── springFormEncoder.encode() -> SpringFormEncoder (feign-form 库)
  │
  └── 其他类型 (application/json, text/plain, application/octet-stream, ...)
        └── encodeWithMessageConverter()
              └── 遍历 HttpMessageConverter 列表
                    ├── GenericHttpMessageConverter.canWrite(genericType, clazz, contentType)?
                    │     └── converter.write(body, genericType, contentType, outputMessage)
                    └── HttpMessageConverter.canWrite(clazz, contentType)?
                          └── converter.write(body, contentType, outputMessage)
                    └── 全部不匹配 -> 抛出 EncodeException
```

### 2.3 步骤详解

#### 步骤 1: 获取 Content-Type

```java
Collection<String> contentTypes = request.headers().get(HttpEncoding.CONTENT_TYPE);
```

从 `RequestTemplate` 的 Header 中获取 `Content-Type`。这个 Header 由以下来源设置:
- `@RequestMapping(consumes = "application/json")` -> `SpringMvcContract` 解析时设置
- `@PostMapping(headers = "Content-Type=...")` -> 显式设置
- 未设置时 -> `requestContentType = null`

**Content-Type 为 null 时的行为**: `canWrite(clazz, null)` 由具体 `HttpMessageConverter` 决定。大多数转换器在 `null` content type 时返回 `true`（如 `MappingJackson2HttpMessageConverter` 可以处理任意类型）。

#### 步骤 2: 表单类型判断

```java
private boolean isFormRelatedContentType(MediaType requestContentType) {
    return isMultipartType(requestContentType) || isFormUrlEncoded(requestContentType);
}

private boolean isMultipartType(MediaType requestContentType) {
    return Arrays.asList(MULTIPART_FORM_DATA, MULTIPART_MIXED, MULTIPART_RELATED).contains(requestContentType);
}

private boolean isFormUrlEncoded(MediaType requestContentType) {
    return Objects.equals(APPLICATION_FORM_URLENCODED, requestContentType);
}
```

四种表单相关 Content-Type:
- `multipart/form-data` -- 文件上传
- `multipart/mixed` -- 混合内容
- `multipart/related` -- 关联内容
- `application/x-www-form-urlencoded` -- URL 编码表单

表单类型委托给 `SpringFormEncoder`（来自 `feign-form-spring` 库），它使用 `FormHttpMessageConverter` 处理 `MultiValueMap` 和 `MultipartFile`。

**测试验证**:
```java
@Test
void testMultipartFile2() {
    Encoder encoder = this.context.getInstance("foo", Encoder.class);
    assertThat(encoder).isNotNull();
    RequestTemplate request = new RequestTemplate();
    request.header(ACCEPT, MediaType.MULTIPART_FORM_DATA_VALUE);
    request.header(CONTENT_TYPE, MediaType.MULTIPART_FORM_DATA_VALUE);

    MultipartFile multipartFile = new MockMultipartFile("test_multipart_file", "hi".getBytes());
    encoder.encode(multipartFile, MultipartFile.class, request);

    assertThat((String) ((List) request.headers().get(CONTENT_TYPE)).get(0))
            .as("Request Content-Type is not multipart/form-data")
            .contains("multipart/form-data; charset=UTF-8; boundary=");
    assertThat(request.headers().get(CONTENT_TYPE).size()).as("There is more than one Content-Type request header")
            .isEqualTo(1);
    assertThat(((List) request.headers().get(ACCEPT)).get(0)).as("Request Accept header is not multipart/form-data")
            .isEqualTo(MULTIPART_FORM_DATA_VALUE);
    assertThat(((List) request.headers().get(CONTENT_LENGTH)).get(0))
            .as("Request Content-Length is not equal to 186").isEqualTo("186");
    assertThat(new String(request.body())).as("Body content cannot be decoded").contains("hi");
}

@Test
void testMultipartFile1() {
    Encoder encoder = this.context.getInstance("foo", Encoder.class);
    assertThat(encoder).isNotNull();
    RequestTemplate request = new RequestTemplate();

    MultipartFile multipartFile = new MockMultipartFile("test_multipart_file", "hi".getBytes());

    Assertions.assertThatExceptionOfType(EncodeException.class)
            .isThrownBy(() -> encoder.encode(multipartFile, MultipartFile.class, request));
}

@Test
void testFromURLEncodedValue() {
    Encoder encoder = context.getInstance("formUrlEncoded", Encoder.class);
    assertThat(encoder).isNotNull();
    RequestTemplate request = new RequestTemplate();
    request.header(CONTENT_TYPE, APPLICATION_FORM_URLENCODED_VALUE);
    String body = "test";
    encoder.encode(body, String.class, request);
    assertThat(new String(request.body())).as("Body content cannot be decoded").contains(body);
}
```

#### 步骤 3: MultipartFile 警告

```java
if (bodyType == MultipartFile.class) {
    log.warn("For MultipartFile to be handled correctly, the 'consumes' parameter of @RequestMapping "
            + "should be specified as MediaType.MULTIPART_FORM_DATA_VALUE");
}
```

当 `MultipartFile` 的 Content-Type 不是 `multipart/form-data` 时，发出警告。这意味着 `MultipartFile` 会进入 `encodeWithMessageConverter()`，但没有合适的 `HttpMessageConverter` 能处理它，最终抛出 `EncodeException`。

#### 步骤 4: encodeWithMessageConverter()

```java
private void encodeWithMessageConverter(Object requestBody, Type bodyType, RequestTemplate request,
        MediaType requestContentType) {
    initConvertersIfRequired();
    for (HttpMessageConverter messageConverter : converters) {
        FeignOutputMessage outputMessage;
        try {
            if (messageConverter instanceof GenericHttpMessageConverter) {
                // 泛型类型: List<String>, Map<String, Object> 等
                outputMessage = checkAndWrite(requestBody, bodyType, requestContentType,
                        (GenericHttpMessageConverter) messageConverter, request);
            }
            else {
                // 普通类型: String, byte[], MyObject 等
                outputMessage = checkAndWrite(requestBody, requestContentType, messageConverter, request);
            }
        }
        catch (IOException | HttpMessageConversionException ex) {
            throw new EncodeException("Error converting request body", ex);
        }
        if (outputMessage != null) {
            // clear headers
            request.headers(null);
            // converters can modify headers, so update the request
            // with the modified headers
            request.headers(new LinkedHashMap<>(outputMessage.getHeaders()));

            // do not use charset for binary data and protobuf
            Charset charset;

            MediaType contentType = outputMessage.getHeaders().getContentType();
            Charset charsetFromContentType = contentType != null ? contentType.getCharset() : null;

            if (encoderProperties != null && encoderProperties.isCharsetFromContentType()
                    && charsetFromContentType != null) {
                charset = charsetFromContentType;  // 从 Content-Type 获取 charset
            }
            else if (shouldHaveNullCharset(messageConverter, outputMessage)) {
                charset = null;  // 二进制/protobuf: 无 charset
            }
            else {
                charset = StandardCharsets.UTF_8;  // 默认 UTF-8
            }
            request.body(outputMessage.getOutputStream().toByteArray(), charset);
            return;
        }
    }
    String message = "Could not write request: no suitable HttpMessageConverter " + "found for request type ["
            + requestBody.getClass().getName() + "]";
    if (requestContentType != null) {
        message += " and content type [" + requestContentType + "]";
    }
    throw new EncodeException(message);
}
```

**转换器遍历逻辑**:
1. 按 `converters` 列表中的注册顺序遍历
2. `GenericHttpMessageConverter` 用 `canWrite(Type, Class, MediaType)` 检查（支持泛型如 `List<String>`）
3. 普通 `HttpMessageConverter` 用 `canWrite(Class, MediaType)` 检查
4. 第一个 `canWrite()` 返回 `true` 的转换器胜出
5. 调用 `converter.write()` 将对象写入 `FeignOutputMessage`

**Header 更新逻辑**:
1. `request.headers(null)` -- **清空**所有旧 Header（包括 RequestInterceptor 设置的）
2. 从 `outputMessage.getHeaders()` 获取转换器设置的 Header（可能修改了 Content-Type，如添加 charset）
3. `request.headers(newHeaders)` -- 设置新 Header

**关键影响**: 清空旧 Header 会清除 `SpringMvcContract` 在 encode 之前从注解中提取并设置的 Header（如 `Accept`、`Content-Type`）。但由于 Feign 的 `RequestInterceptor` 在 `encode()` **之后**执行（见第六节时序图），拦截器设置的 Header（如 `Content-Encoding`、`X-Trace-Id`）不会被清空。这是 A-05 压缩拦截器设置 `Content-Encoding: gzip` 能正常生效的原因。

**charset 决策逻辑**:

| 条件 | charset | 场景 |
|------|---------|------|
| `charsetFromContentType=true` + Content-Type 有 charset | 从 Content-Type 获取 | 显式启用时 |
| `shouldHaveNullCharset()` = true | `null` | 二进制/protobuf |
| 其他 | `UTF-8` | 默认（文本类型） |

`shouldHaveNullCharset()`:
```java
private boolean shouldHaveNullCharset(HttpMessageConverter messageConverter, FeignOutputMessage outputMessage) {
    return binaryContentType(outputMessage) || messageConverter instanceof ByteArrayHttpMessageConverter
            || messageConverter instanceof ProtobufHttpMessageConverter && ProtobufHttpMessageConverter.PROTOBUF
                    .isCompatibleWith(outputMessage.getHeaders().getContentType());
}

protected boolean binaryContentType(FeignOutputMessage outputMessage) {
    MediaType contentType = outputMessage.getHeaders().getContentType();
    return contentType == null || Stream
        .of(MediaType.APPLICATION_CBOR, MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_PDF,
                MediaType.IMAGE_GIF, MediaType.IMAGE_JPEG, MediaType.IMAGE_PNG)
        .anyMatch(mediaType -> mediaType.includes(contentType));
}
```

**测试验证**:
```java
@Test
void testBinaryData() {
    Encoder encoder = this.context.getInstance("foo", Encoder.class);
    assertThat(encoder).isNotNull();

    RequestTemplate request = new RequestTemplate();

    encoder.encode("hi".getBytes(), null, request);

    assertThat(((List) request.headers().get(CONTENT_TYPE)).get(0)).as("Request Content-Type is not octet-stream")
            .isEqualTo(APPLICATION_OCTET_STREAM_VALUE);
}

@Test
void testNoCharsetForBinaryFiles() {
    Encoder encoder = context.getInstance("test", Encoder.class);
    assertThat(encoder).isNotNull();
    RequestTemplate request = new RequestTemplate();
    request.header(CONTENT_TYPE, APPLICATION_OCTET_STREAM_VALUE);
    Resource resource = applicationContext.getResource("classpath:dummy.pdf");

    encoder.encode(resource, Resource.class, request);

    assertThat(request.requestBody().getEncoding()).isEmpty();
}

@Test
void testUTF8CharsetForTextFiles() {
    Encoder encoder = context.getInstance("test", Encoder.class);
    assertThat(encoder).isNotNull();
    RequestTemplate request = new RequestTemplate();
    request.header(CONTENT_TYPE, TEXT_PLAIN_VALUE);
    String test = "test";

    encoder.encode(test, String.class, request);

    assertThat(request.requestBody().getEncoding().get().name()).isEqualTo("UTF-8");
}
```

### 2.4 checkAndWrite() - 两个重载

```java
// 普通类型
@SuppressWarnings("unchecked")
private FeignOutputMessage checkAndWrite(Object body, MediaType contentType, HttpMessageConverter converter,
        RequestTemplate request) throws IOException {
    if (converter.canWrite(body.getClass(), contentType)) {
        logBeforeWrite(body, contentType, converter);
        FeignOutputMessage outputMessage = new FeignOutputMessage(request);
        converter.write(body, contentType, outputMessage);
        return outputMessage;
    }
    else {
        return null;
    }
}

// 泛型类型
@SuppressWarnings("unchecked")
private FeignOutputMessage checkAndWrite(Object body, Type genericType, MediaType contentType,
        GenericHttpMessageConverter converter, RequestTemplate request) throws IOException {
    if (converter.canWrite(genericType, body.getClass(), contentType)) {
        logBeforeWrite(body, contentType, converter);
        FeignOutputMessage outputMessage = new FeignOutputMessage(request);
        converter.write(body, genericType, contentType, outputMessage);
        return outputMessage;
    }
    else {
        return null;
    }
}
```

**区别**: 泛型版本使用 `Type` 参数（如 `ParameterizedType` `List<String>`），普通版本使用 `Class` 参数。泛型版本调用 `canWrite(Type, Class, MediaType)` 和 `write(Object, Type, MediaType, HttpOutputMessage)`。

**测试验证**:
```java
@Test
void testCustomHttpMessageConverter() {
    Encoder encoder = this.context.getInstance("foo", Encoder.class);
    assertThat(encoder).isNotNull();
    RequestTemplate request = new RequestTemplate();

    encoder.encode("hi", MyType.class, request);

    Collection<String> contentTypeHeader = request.headers().get("Content-Type");
    assertThat(contentTypeHeader).as("missing content type header").isNotNull();
    assertThat(contentTypeHeader.isEmpty()).as("missing content type header").isFalse();

    String header = contentTypeHeader.iterator().next();
    assertThat(header).as("content type header is wrong").isEqualTo("application/mytype");

    assertThat(request.requestCharset()).as("request charset is null").isNotNull();
    assertThat(request.requestCharset()).as("request charset is wrong").isEqualTo(StandardCharsets.UTF_8);
}

// gh-225
@Test
void testCustomGenericHttpMessageConverter() {
    Encoder encoder = this.context.getInstance("foo", Encoder.class);
    assertThat(encoder).isNotNull();
    RequestTemplate request = new RequestTemplate();

    ParameterizedTypeReference<List<String>> stringListType = new ParameterizedTypeReference<>() {
    };

    request.header(HttpEncoding.CONTENT_TYPE, "application/mygenerictype");
    encoder.encode(Collections.singletonList("hi"), stringListType.getType(), request);

    Collection<String> contentTypeHeader = request.headers().get("Content-Type");
    assertThat(contentTypeHeader).as("missing content type header").isNotNull();
    assertThat(contentTypeHeader.isEmpty()).as("missing content type header").isFalse();

    String header = contentTypeHeader.iterator().next();
    assertThat(header).as("content type header is wrong").isEqualTo("application/mygenerictype");

    assertThat(request.requestCharset()).as("request charset is null").isNotNull();
    assertThat(request.requestCharset()).as("request charset is wrong").isEqualTo(StandardCharsets.UTF_8);
}
```

### 2.5 FeignOutputMessage - 适配器

```java
protected final class FeignOutputMessage implements HttpOutputMessage {

    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    private final HttpHeaders httpHeaders;

    private FeignOutputMessage(RequestTemplate request) {
        httpHeaders = getHttpHeaders(request.headers());  // 从请求模板复制 Header
    }

    @Override
    public OutputStream getBody() { return outputStream; }

    @Override
    public HttpHeaders getHeaders() { return httpHeaders; }

    public ByteArrayOutputStream getOutputStream() { return outputStream; }
}
```

**适配器模式**: 将 Feign 的 `RequestTemplate` 包装为 Spring 的 `HttpOutputMessage`:
- `getBody()`: 返回 `ByteArrayOutputStream`，`HttpMessageConverter.write()` 将序列化后的字节写入此流
- `getHeaders()`: 返回 `HttpHeaders`，从 `RequestTemplate` 的 Header 复制，转换器可以修改（如设置 Content-Type）

`getHttpHeaders()` 在 `FeignUtils` 中:
```java
// FeignUtils
static HttpHeaders getHttpHeaders(Map<String, Collection<String>> headers) {
    HttpHeaders httpHeaders = new HttpHeaders();
    for (Map.Entry<String, Collection<String>> entry : headers.entrySet()) {
        httpHeaders.put(entry.getKey(), new ArrayList<>(entry.getValue()));
    }
    return httpHeaders;
}
```

## 三、SpringDecoder.decode() 逐行分析

### 3.1 完整源码

```java
@Override
public Object decode(final Response response, Type type) throws IOException, FeignException {
    if (type instanceof Class || type instanceof ParameterizedType || type instanceof WildcardType) {
        List<HttpMessageConverter<?>> converters = messageConverters.getObject().getConverters();
        customizers.forEach(customizer -> customizer.accept(converters));
        @SuppressWarnings({ "unchecked", "rawtypes" })
        HttpMessageConverterExtractor<?> extractor = new HttpMessageConverterExtractor(type, converters);

        return extractor.extractData(new FeignResponseAdapter(response));
    }
    throw new DecodeException(response.status(), "type is not an instance of Class or ParameterizedType: " + type,
            response.request());
}
```

### 3.2 类型检查

```java
if (type instanceof Class || type instanceof ParameterizedType || type instanceof WildcardType) {
```

**支持的返回类型**:
- `Class`: 如 `String.class`, `MyObject.class`
- `ParameterizedType`: 如 `List<String>`, `Map<String, Object>`, `ResponseEntity<Invoice>`
- `WildcardType`: 如 `? extends Number`

**不支持的类型**: `GenericArrayType`（如 `T[]`），抛出 `DecodeException`。

### 3.3 HttpMessageConverterExtractor

```java
List<HttpMessageConverter<?>> converters = messageConverters.getObject().getConverters();
customizers.forEach(customizer -> customizer.accept(converters));
HttpMessageConverterExtractor<?> extractor = new HttpMessageConverterExtractor(type, converters);
return extractor.extractData(new FeignResponseAdapter(response));
```

`HttpMessageConverterExtractor` 是 Spring Web 的工具类:
1. 从 `FeignResponseAdapter` 获取响应的 `InputStream` 和 `HttpHeaders`
2. 从 `Content-Type` header 确定响应的 `MediaType`
3. 遍历 `HttpMessageConverter` 列表，调用 `canRead(clazz, mediaType)` 检查
4. 第一个匹配的转换器调用 `read(clazz, inputMessage)` 反序列化
5. 返回反序列化后的 Java 对象

### 3.4 FeignResponseAdapter - 适配器

```java
private final class FeignResponseAdapter implements ClientHttpResponse {

    private final Response response;

    private FeignResponseAdapter(Response response) {
        this.response = response;
    }

    @Override
    public HttpStatusCode getStatusCode() {
        return HttpStatusCode.valueOf(response.status());
    }

    @Override
    public int getRawStatusCode() {
        return response.status();
    }

    @Override
    public String getStatusText() {
        return response.reason();
    }

    @Override
    public void close() {
        try {
            response.body().close();
        }
        catch (IOException ex) {
            // Ignore exception on close...
        }
    }

    @Override
    public InputStream getBody() throws IOException {
        return response.body() != null ? response.body().asInputStream() : null;
    }

    @Override
    public HttpHeaders getHeaders() {
        return getHttpHeaders(response.headers());
    }
}
```

**适配器模式**: 将 Feign `Response` 包装为 Spring 的 `ClientHttpResponse`:

| ClientHttpResponse 方法 | Feign Response 方法 | 说明 |
|------------------------|--------------------|----|
| `getStatusCode()` | `response.status()` | HTTP 状态码（`HttpStatusCode`） |
| `getRawStatusCode()` | `response.status()` | 原始 int 状态码 |
| `getStatusText()` | `response.reason()` | HTTP 状态文本 |
| `getBody()` | `response.body().asInputStream()` | 响应体输入流 |
| `getHeaders()` | `getHttpHeaders(response.headers())` | 响应头 |
| `close()` | `response.body().close()` | 关闭响应体 |

**`getBody()` 的 null 检查**: `response.body() != null ? ... : null`。当响应无 body（如 204 No Content）时返回 null。

**测试验证**:
```java
@Test
void shouldNotThrownNPEWhenNoContent() {
    assertThatCode(
            () -> decoder.decode(Response.builder().request(mock(Request.class)).status(200).build(), String.class))
                    .doesNotThrowAnyException();
}
```

验证无 body 响应不会抛出 NPE（issue #972 修复）。

**`close()` 的异常忽略**: `response.body().close()` 可能抛出 `IOException`（如网络连接已断开），但 `close()` 方法捕获并忽略。这是合理的 -- 关闭时的异常不应影响业务逻辑。

## 四、Decoder 包装链

### 4.1 三层包装

在 `FeignClientsConfiguration` 中创建:

```java
@Bean @ConditionalOnMissingBean
public Decoder feignDecoder(ObjectProvider<HttpMessageConverterCustomizer> customizers) {
    return new OptionalDecoder(new ResponseEntityDecoder(new SpringDecoder(messageConverters, customizers)));
}
```

```
客户端代码 <- OptionalDecoder <- ResponseEntityDecoder <- SpringDecoder
                  │                    │                     │
             Optional<T>          ResponseEntity<T>       普通类型 T
             404->empty()         保留状态码/头          HttpMessageConverter
```

### 4.2 OptionalDecoder

来自 Feign 核心库（`feign.optionals.OptionalDecoder`，feign-core 13.3）:
- 当返回类型是 `Optional<T>` 且状态码为 **404 或 204** 时，返回 `Optional.empty()`
- 否则委托给内层 Decoder（先解出 `Optional` 内的类型再解码）

### 4.3 ResponseEntityDecoder

来自 Spring Cloud OpenFeign（`org.springframework.cloud.openfeign.support.ResponseEntityDecoder`）:
- 当返回类型是参数化的 `HttpEntity`（含 `ResponseEntity<T>`）时，先解出 `T` 委托内层 Decoder，再包装回 `ResponseEntity`（保留状态码和响应头）
- 当返回类型是非参数化 `HttpEntity` 时，`createResponse(null, response)`
- 否则委托给内层 Decoder

### 4.4 SpringDecoder

最内层，委托给 `HttpMessageConverterExtractor` 反序列化。

## 五、Encoder 创建链

### 5.1 FeignClientsConfiguration 中的创建

```java
@Bean
@ConditionalOnMissingBean
@ConditionalOnMissingClass("org.springframework.data.domain.Pageable")
public Encoder feignEncoder(ObjectProvider<AbstractFormWriter> formWriterProvider,
        ObjectProvider<HttpMessageConverterCustomizer> customizers) {
    return springEncoder(formWriterProvider, encoderProperties, customizers);
}

private Encoder springEncoder(ObjectProvider<AbstractFormWriter> formWriterProvider,
        FeignEncoderProperties encoderProperties, ObjectProvider<HttpMessageConverterCustomizer> customizers) {
    AbstractFormWriter formWriter = formWriterProvider.getIfAvailable();

    if (formWriter != null) {
        return new SpringEncoder(new SpringPojoFormEncoder(formWriter), messageConverters, encoderProperties,
                customizers);
    }
    else {
        return new SpringEncoder(new SpringFormEncoder(), messageConverters, encoderProperties, customizers);
    }
}
```

**`@ConditionalOnMissingClass("org.springframework.data.domain.Pageable")`**: 当 Spring Data 不在 classpath 时使用此 Encoder。如果 Spring Data 在 classpath 上，由 `feignEncoderPageable()`（4.1.3 中位于 `FeignClientsConfiguration` 主类，见 D5 §3.5）提供 `PageableSpringEncoder`（包装 `SpringEncoder`）。

### 5.2 SpringPojoFormEncoder

```java
private class SpringPojoFormEncoder extends SpringFormEncoder {

    SpringPojoFormEncoder(AbstractFormWriter formWriter) {
        super();
        MultipartFormContentProcessor processor = (MultipartFormContentProcessor) getContentProcessor(MULTIPART);
        processor.addFirstWriter(formWriter);
    }
}
```

当 `AbstractFormWriter` Bean 存在时，用 `SpringPojoFormEncoder` 替代 `SpringFormEncoder`，将自定义 form writer 添加到 multipart 处理器的首位。

## 六、编码执行时序

```
Feign 代理.method(args)
  │
  ├── SynchronousMethodHandler.invoke()
  │     │
  │     ├── 1. buildTemplateFromArgs.create(argv)
  │     │     ├── SpringMvcContract -> RequestTemplate (含 @RequestParam/@PathVariable 等)
  │     │     └── encoder.encode(body, type, template)  ← SpringEncoder
  │     │           ├── [表单] springFormEncoder.encode() -> 直接写入 body, return
  │     │           └── [非表单] encodeWithMessageConverter()
  │     │                 ├── 遍历 HttpMessageConverter 列表
  │     │                 ├── MappingJackson2HttpMessageConverter.canWrite()? -> true
  │     │                 ├── converter.write(body, contentType, FeignOutputMessage)
  │     │                 │     └── ObjectMapper.writeValue(ByteArrayOutputStream, body)
  │     │                 ├── request.headers(null)  ← 清空旧 Header!
  │     │                 ├── request.headers(newHeaders)  ← 用转换器设置的 Header
  │     │                 └── request.body(bytes, charset)  ← 设置序列化后的 body
  │     │
  │     ├── 2. RequestInterceptors.apply(template)  ← 在 encode 之后!
  │     │     └── TraceIdInterceptor / VersionInterceptor / FeignContentGzipEncodingInterceptor
  │     │         (此时设置的 Header 不会被 encode() 清空)
  │     │
  │     └── 3. Client.execute(request, options)
  │           └── FeignBlockingLoadBalancerClient (D3)
  │                 └── ApacheHttp5Client.execute()
  │
  └── SpringDecoder.decode(response, type)
        └── HttpMessageConverterExtractor.extractData(FeignResponseAdapter)
              └── MappingJackson2HttpMessageConverter.read()
                    └── ObjectMapper.readValue(InputStream, type)
```

**Feign `SynchronousMethodHandler` 执行顺序**:

```java
// feign.SynchronousMethodHandler
public Object invoke(Object[] argv) throws Throwable {
    RequestTemplate template = buildTemplateFromArgs.create(argv);  // 1. encode (内部调用 encoder.encode())
    // ...
    return executeAndDecode(template, options);                     // 2. execute
}

Object executeAndDecode(RequestTemplate template, Options options) throws Throwable {
    Request request = targetRequest(template);  // 2a. 应用 RequestInterceptor (在 encode 之后!)
    // ...
    response = client.execute(request, options);  // 2b. 发送请求
}

Request targetRequest(RequestTemplate template) {
    for (RequestInterceptor interceptor : requestInterceptors) {
        interceptor.apply(template);  // 拦截器在此执行
    }
    return target.apply(template);
}
```

> feign-core 13.3 的定位: `buildTemplateFromArgs` 由 `RequestTemplateFactoryResolver` 提供；`BuildEncodedTemplateFromArgs.create()` 内部调用 `encoder.encode(body, metadata.bodyType(), mutable)`（`:271/:273`），表单场景在 `BuildFormEncodedTemplateFromArgs.create()`（`:235`）。

**关键结论**: `encoder.encode()` 在 `RequestInterceptor.apply()` **之前** 执行。这意味着:
- `encode()` 中的 `request.headers(null)` 清空的是 `SpringMvcContract` 设置的 Header（如 `Accept`、`Content-Type`）
- `RequestInterceptor` 在 `encode()` 之后执行，其设置的 Header（如 `Content-Encoding`、`X-Trace-Id`）**不会被清空**

这解释了 A-05 中 `FeignContentGzipEncodingInterceptor` 设置 `Content-Encoding: gzip` 不会被 `encode()` 清空的原因。

## 七、测试分析

### 7.1 SpringEncoderTests (370 行)

| 测试方法 | 验证内容 |
|---------|---------|
| `testCustomHttpMessageConverter` | 自定义 `HttpMessageConverter` 被选中，Content-Type 正确 |
| `testCustomGenericHttpMessageConverter` | 泛型 `GenericHttpMessageConverter` 处理 `List<String>` |
| `testBinaryData` | `byte[]` 使用 `ByteArrayHttpMessageConverter`，Content-Type = octet-stream |
| `testMultipartFile1` | `MultipartFile` 无 Content-Type -> 抛 `EncodeException` |
| `testMultipartFile2` | `MultipartFile` + `multipart/form-data` -> `SpringFormEncoder` 处理 |
| `testFromURLEncodedValue` | `application/x-www-form-urlencoded` -> `SpringFormEncoder` 处理 |
| `testNoCharsetForBinaryFiles` | 二进制文件 (PDF) -> charset = null |
| `testUTF8CharsetForTextFiles` | 文本文件 -> charset = UTF-8 |

### 7.2 SpringDecoderTests (56 行)

| 测试方法 | 验证内容 |
|---------|---------|
| `shouldNotThrownNPEWhenNoContent` | 无 body 响应不抛 NPE (issue #972) |

## 八、关键设计决策

### 8.1 转换器为什么延迟初始化?

```java
private List<HttpMessageConverter<?>> converters;  // 非 final，首次 encode 赋值
```

- **延迟**: `messageConverters.getObject()` 在首次 `encode()` 时才调用（`initConvertersIfRequired()`），避免启动时立即构建转换器列表
- **可定制**: `customizers` 在列表生成后立即应用（`accept`），保证定制发生在遍历之前
- **注意**: 4.1.3 的初始化**未加锁**（`converters == null` 简单判断）；并发首次编码理论上可能重复初始化，实际场景（单客户端首次调用串行化）风险低。4.3.2 的 `FeignHttpMessageConverters` 才引入双重检查锁。

### 8.2 为什么 encode() 要清空并替换 Headers?

```java
request.headers(null);  // 清空
request.headers(new LinkedHashMap<>(outputMessage.getHeaders()));  // 用转换器设置的 Header 替换
```

`HttpMessageConverter.write()` 可能会修改 Content-Type 头（如添加 charset 参数: `application/json` -> `application/json;charset=UTF-8`）。清空后替换确保 Content-Type 与编码后的内容一致。

**副作用**: `SpringMvcContract` 在 encode 之前设置的 Header（如 `Accept`）也会被清空。但由于 `RequestInterceptor` 在 encode 之后执行，拦截器设置的 Header 不受影响。

### 8.3 为什么表单类型委托给 SpringFormEncoder?

`SpringFormEncoder`（来自 `feign-form-spring` 库）专门处理:
- `multipart/form-data`: 文件上传，生成 boundary 分隔符
- `application/x-www-form-urlencoded`: URL 编码表单

这些格式需要特殊的字节流构造（boundary、multipart 分隔符），不适合用通用的 `HttpMessageConverter` 处理。

### 8.4 charset 决策为什么有三种路径?

| 路径 | 条件 | 原因 |
|------|------|------|
| 从 Content-Type 获取 | `charsetFromContentType=true` | 用户显式要求尊重 Content-Type 中的 charset |
| null | 二进制/protobuf | 二进制数据不应有 charset（charset 只适用于文本） |
| UTF-8 | 默认 | 文本数据默认 UTF-8，确保跨语言兼容 |

### 8.5 为什么 Decoder 不需要表单处理?

Decoder 委托给 `HttpMessageConverterExtractor`，后者使用 Spring 的 `HttpMessageConverter.read()` 反序列化。`FormHttpMessageConverter` 可以读取 `multipart/form-data` 响应（如文件下载），无需特殊处理。

Encoder 需要特殊处理是因为**生成** multipart boundary 比解析更复杂。

## 九、设计模式总结

| 模式 | 应用位置 | 说明 |
|------|---------|------|
| **Adapter** | `FeignOutputMessage` (Feign -> Spring HttpOutputMessage) | 适配 Feign RequestTemplate 为 Spring 输出消息 |
| **Adapter** | `FeignResponseAdapter` (Feign -> Spring ClientHttpResponse) | 适配 Feign Response 为 Spring 客户端响应 |
| **Decorator** | `OptionalDecoder` -> `ResponseEntityDecoder` -> `SpringDecoder` | 层层包装增强解码能力 |
| **Decorator** | `PageableSpringEncoder` -> `SpringEncoder` | 增加分页参数处理 |
| **Strategy** | `HttpMessageConverter` 链 | 第一个 `canWrite()`/`canRead()` 匹配的转换器胜出 |
| **Template Method** | `AbstractGenericHttpMessageConverter` | Spring 的转换器基类 |
| **Lazy Init** | `SpringEncoder.converters`（首次 encode 初始化） | 延迟获取转换器列表 |

## 十、与 D1/D5 的衔接

```
D5: FeignClientsConfiguration (子容器)
  ├── feignDecoder() -> OptionalDecoder(ResponseEntityDecoder(SpringDecoder))
  └── feignEncoder() -> SpringEncoder(SpringFormEncoder)
        ↓
D1: FeignClientFactoryBean.feign()
  ├── get(context, Encoder.class) -> SpringEncoder
  └── get(context, Decoder.class) -> OptionalDecoder(ResponseEntityDecoder(SpringDecoder))
        ↓
D1: FeignClientFactoryBean.configureUsingConfiguration()
  └── builder.encoder(get(context, Encoder.class))
  └── builder.decoder(get(context, Decoder.class))
        ↓
Feign 运行时:
  ├── SynchronousMethodHandler.invoke()
  │     ├── encoder.encode(body, type, template)  ← SpringEncoder
  │     └── decoder.decode(response, type)        ← SpringDecoder
  └── SpringMvcContract -> 解析 @RequestMapping 注解
```

## 十一、本 Lab 中的实际映射

| Lab 场景 | 对应源码路径 |
|---------|-------------|
| A-05 压缩测试发送大 body | `encode()` -> `encodeWithMessageConverter()` -> `MappingJackson2HttpMessageConverter` -> JSON 序列化 |
| A-05 Content-Encoding 不被清空 | `encode()` 中 `request.headers(null)` 在 `RequestInterceptor` 之前执行 |
| A-05 `@RequestBody String body` | `StringHttpMessageConverter.canWrite(String, null)` -> `write()` -> Content-Type: `text/plain;charset=UTF-8` |
| A-06 Sentinel 返回 `Map<String, Object>` fallback | `decode()` -> `HttpMessageConverterExtractor` -> `MappingJackson2HttpMessageConverter.read()` |
| A-08 V2 返回 `Map<String, Object>` | 同上 |
| A-09 RestClient 对比 | RestClient 使用 `RestClient.Builder` + `HttpMessageConverter`，与 SpringEncoder 共享同一转换器体系 |
