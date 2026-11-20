package com.mindgap.app.cloud

/**
 * 云端找茬题服务地址。
 * 模拟器访问宿主机用 10.0.2.2；真机改成开发机/服务器的局域网 IP。
 * Debug 构建允许明文 HTTP；Release 请换成 HTTPS 域名。
 */
object CloudConfig {
    const val BASE_URL = "http://10.0.2.2:8000"
    const val GENERATE_PATH = "/v1/quiz/generate"
}
