import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.websocket.*
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javafx.scene.layout.VBox
import javafx.scene.text.Text
import kotlinx.coroutines.*
import java.net.URI

class LCUManager {
	lateinit var ttt: Text
	lateinit var vbb: VBox

	private lateinit var ssl_context: SSLContext
	private lateinit var auth: String
	private lateinit var prefix: String
	private lateinit var port: String

	var champ_select = false

	fun initalize_certificate() {
		val certificate_file = File("riotgames.pem")
		val certificateFactory = CertificateFactory.getInstance("X.509")
		val certificate = certificateFactory.generateCertificate(certificate_file.inputStream())

		val keyStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
		keyStore.load(null, null)
		keyStore.setCertificateEntry("ca", certificate)

		val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
		trustManagerFactory.init(keyStore)

		ssl_context = SSLContext.getInstance("TLS")
		ssl_context.init(null, trustManagerFactory.trustManagers, null)
	}

	private fun get_endpoint(endpoint: String): JsonElement {
		val url = "$prefix://127.0.0.1:$port/$endpoint"
		val urlConnection = URI(url).toURL().openConnection() as HttpsURLConnection
		urlConnection.sslSocketFactory = ssl_context.socketFactory
		urlConnection.requestMethod = "GET"
		urlConnection.setRequestProperty("Authorization", "Basic $auth")

		return JsonParser.parseReader(urlConnection.inputStream.reader())
	}

	fun initialize_lockfile() {
		val data = File("C:\\Riot Games\\League of Legends\\lockfile").readLines()[0]
		val data_arr = data.split(":")
		port = data_arr[2]
		val password = data_arr[3]
		prefix = data_arr[4]

		val riot_pass = "riot:$password"
		auth = Base64.getEncoder().encodeToString(riot_pass.toByteArray())
	}

	fun initialize_websocket_listener() = runBlocking {
		val client = HttpClient(CIO) {
			install(WebSockets)
			engine {
				https {
					trustManager = object : X509TrustManager {
						override fun checkClientTrusted(p0: Array<out X509Certificate>?, p1: String?) {}

						override fun checkServerTrusted(p0: Array<out X509Certificate>?, p1: String?) {}

						override fun getAcceptedIssuers(): Array<X509Certificate>? = null
					}
				}
			}
		}

		CoroutineScope(Dispatchers.Main).launch {
			client.wss(
				method = HttpMethod.Get,
				host = "127.0.0.1",
				port = port.toInt(),
				path = "/",
				request = {
					header("Authorization", "Basic $auth")
				}
			) {
				send("[5, \"OnJsonApiEvent_lol-champ-select_v1_session\"]")
				//send("[5, \"OnJsonApiEvent_lol-gameflow_v1_session\"]")
				while (true) {
					val inc = incoming.receive() as Frame.Text
					val text = inc.readText()
					println(text)
					if (text == "") continue
					val data = JsonParser.parseString(text).asJsonArray[2]
					println(data)
					if (data.asJsonObject["eventType"].asString == "Create") {
						champ_select = true
						ttt.text = "Champ Select"
					} else if (data.asJsonObject["eventType"].asString == "Delete") {
						champ_select = false
						ttt.text = "Not in Champ Select"
					}
					vbb.children.add(Text(text))
				}
			}
		}
	}

	fun initialize() {
		println("init")
		initialize_lockfile()
		initalize_certificate()
		initialize_websocket_listener()
	}
}
