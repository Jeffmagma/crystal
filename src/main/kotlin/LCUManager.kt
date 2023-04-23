import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.websocket.*
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.scene.control.TableColumn
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javafx.scene.layout.VBox
import javafx.scene.control.TableView
import javafx.scene.text.Text
import kotlinx.coroutines.*
import kotlinx.coroutines.javafx.JavaFx
import java.net.URI
import kotlin.system.exitProcess

class LCUManager {
	val data = FXCollections.observableArrayList<Champion>()
	lateinit var table: TableView<Champion>

	// javafx stuff
	lateinit var ttt: Text
	lateinit var vbb: VBox

	private lateinit var ssl_context: SSLContext
	private lateinit var auth: String
	private lateinit var prefix: String
	private lateinit var port: String

	private var champ_select = SimpleBooleanProperty(false)

	// champion id -> name
	private val champions_map = mutableMapOf<Int, String>()
	// challenge id -> (challenge name, list of champion ids)
	private val challenges_map = mutableMapOf<Int, Pair<String, List<Int>>>()
	// champion id -> mastery level
	private val masteries_map = mutableMapOf<Int, Int>()

	private fun get_generic(endpoint: String): JsonElement {
		val urlConnection = URI(endpoint).toURL().openConnection() as HttpsURLConnection
		urlConnection.requestMethod = "GET"

		return JsonParser.parseReader(urlConnection.inputStream.reader())
	}

	private fun get_version(): String {
		val version = get_generic("https://ddragon.leagueoflegends.com/api/versions.json")
		return version.asJsonArray[0].asString
	}

	private fun get_champ_select(): List<Int> {
		val champ_select = get_endpoint("lol-champ-select/v1/session")
		val bench = champ_select.asJsonObject["benchChampions"].asJsonArray.map { it.asJsonObject["championId"].asInt }
		val my_team = champ_select.asJsonObject["myTeam"].asJsonArray.map { it.asJsonObject["championId"].asInt }
		return bench + my_team
	}

	private fun init_champions() {
		val version = get_version()
		val champions = get_generic("https://ddragon.leagueoflegends.com/cdn/$version/data/en_US/champion.json")
		champions.asJsonObject["data"].asJsonObject.entrySet().forEach {
			val champion = it.value.asJsonObject
			val name = champion["name"].asString
			val id = champion["key"].asInt
			champions_map[id] = name
		}
	}

	private fun init_summoner(): String {
		val summoner = get_endpoint("lol-summoner/v1/current-summoner")
		return summoner.asJsonObject["summonerId"].asString
	}

	private fun update_masteries() {
		val summoner_id = init_summoner()
		val masteries = get_endpoint("lol-collections/v1/inventories/$summoner_id/champion-mastery")
		masteries.asJsonArray.forEach {
			masteries_map[it.asJsonObject["championId"].asInt] = it.asJsonObject["championLevel"].asInt
		}
	}

	private fun update_challenges() {
		val challenges = get_endpoint("lol-challenges/v1/challenges/local-player")
		challenges.asJsonObject.entrySet().forEach {
			val value = it.value.asJsonObject
			if (!value["completedIds"].asJsonArray.isEmpty && value["idListType"].asString == "CHAMPION"
				&& value["category"].asString != "COLLECTION"
				&& value["category"].asString != "LEGACY"
				&& !value["name"].asString.contains("Master")) {
				challenges_map[it.key.toInt()] = Pair(value["name"].asString, value["completedIds"].asJsonArray.map { it.asInt })
			}
		}
	}

	private fun initalize_certificate() {
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

	private fun get_client_state(): String {
		val data = get_endpoint("lol-gameflow/v1/session")
		return data.asJsonObject["phase"].asString
	}

	private fun init_1() {
		champ_select.addListener { _, _, value ->
			if (value) {
				ttt.text = "Champ select"
			} else {
				ttt.text = "Not champ select"
			}
		}
		val state = get_client_state()
		println(state)
		// invalidate to trigger it, not sure how else to do it
		champ_select.set(true)
		champ_select.set(state == "ChampSelect")
	}

	private fun initialize_lockfile() {
		val lockfile = File("C:\\Riot Games\\League of Legends\\lockfile")
		if (!lockfile.exists()) {
			println("lockfile not found, is league running?")
			exitProcess(1)
		}
		val data = lockfile.readLines()[0]
		val data_arr = data.split(":")
		port = data_arr[2]
		val password = data_arr[3]
		prefix = data_arr[4]

		val riot_pass = "riot:$password"
		auth = Base64.getEncoder().encodeToString(riot_pass.toByteArray())
	}

	private fun initialize_websocket_listener() = runBlocking {
		//TODO: use certificate instead of allowing everything
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

		CoroutineScope(Dispatchers.JavaFx).launch {
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
						champ_select.set(true)
					} else if (data.asJsonObject["eventType"].asString == "Delete") {
						champ_select.set(false)
					}
					vbb.children.add(Text(text))
				}
			}
		}
	}

	data class Champion(val id: Int, val name: SimpleStringProperty, val mastery: SimpleIntegerProperty, val challenges: Map<Int, SimpleBooleanProperty>)

	private fun initialize_ui() {
		table.items = data
		val name_column = TableColumn<Champion, String>("Champion")
		name_column.setCellValueFactory {
			it.value.name
		}
		val mastery_column = TableColumn<Champion, Int>("Mastery")
		mastery_column.setCellValueFactory {
			it.value.mastery.asObject()
		}
		table.columns.addAll(name_column, mastery_column)

		challenges_map.forEach { it ->
			val challenge = it.value
			val id = it.key
			val name = challenge.first
			val column = TableColumn<Champion, Boolean>(name)
			column.setCellValueFactory {
				it.value.challenges[id]
			}
			table.columns.add(column)
		}
	}

	private fun update_ui() {
		data.clear()
		champions_map.forEach { it ->
			val name = it.value
			val id = it.key
			val mastery = masteries_map[id] ?: 0
			val challenges = mutableMapOf<Int, SimpleBooleanProperty>()
			challenges_map.forEach {
				val challenge = it.value
				val challenge_id = it.key
				val completed = challenge.second.contains(id)
				challenges[challenge_id] = SimpleBooleanProperty(completed)
			}
			data.add(Champion(id, SimpleStringProperty(name), SimpleIntegerProperty(mastery), challenges))
		}
	}

	fun initialize() {
		initialize_lockfile()
		initalize_certificate()
		initialize_websocket_listener()
		init_1()
		init_summoner()
		init_champions()
		update_masteries()
		update_challenges()
		initialize_ui()
		update_ui()
	}
}
