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
import javafx.collections.transformation.FilteredList
import javafx.collections.transformation.SortedList
import javafx.event.ActionEvent
import javafx.scene.control.*
import javafx.scene.control.Alert.AlertType
import javafx.scene.text.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URI
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*
import java.util.function.Predicate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.system.exitProcess

fun show_error(message: String) {
	val alert = Alert(AlertType.ERROR)
	alert.contentText = message
	alert.headerText = null
	alert.showAndWait()
}

class LeagueData {
	companion object {
		// champion id -> name
		val champions_map = mutableMapOf<Int, String>()

		private fun get_generic(endpoint: String): JsonElement {
			val urlConnection = URI(endpoint).toURL().openConnection() as HttpsURLConnection
			urlConnection.requestMethod = "GET"

			return JsonParser.parseReader(urlConnection.inputStream.reader())
		}

		private fun get_version(): String {
			val version = get_generic("https://ddragon.leagueoflegends.com/api/versions.json")
			return version.asJsonArray[0].asString
		}

		fun init_champions() {
			val version = get_version()
			val champions = get_generic("https://ddragon.leagueoflegends.com/cdn/$version/data/en_US/champion.json")
			champions.asJsonObject["data"].asJsonObject.entrySet().forEach {
				val champion = it.value.asJsonObject
				val name = champion["name"].asString
				val id = champion["key"].asInt
				champions_map[id] = name
			}
		}

		fun update_challenge_data() {

		}
	}
}

class CrystalUI {
	lateinit var setstatus: Button
	lateinit var message: TextField
	lateinit var refresh: Button
	lateinit var search: TextField
	private val data = FXCollections.observableArrayList<Champion>()
	private val filtered_data = FilteredList(data)
	private val sorted_filtered = SortedList(filtered_data)
	lateinit var champ_select_table: TableView<Champion>
	lateinit var all_champ_table: TableView<Champion>
	lateinit var status: Text
	private var current_champs = listOf<String>()

	private lateinit var ssl_context: SSLContext
	private lateinit var auth: String
	private lateinit var prefix: String
	private lateinit var port: String

	private var gameflow_phase = SimpleStringProperty("None")

	data class Challenge(val id: Int, val name: String, val description: String, val completion: List<Int>)
	private val challenges_list = mutableListOf<Challenge>()
	// champion id -> mastery level
	private val masteries_map = mutableMapOf<Int, Int>()

	private fun update_champ_select() {
		val champ_select = get_endpoint("lol-champ-select/v1/session")
		val bench = champ_select.asJsonObject["benchChampions"].asJsonArray.map { it.asJsonObject["championId"].asInt }
		val my_team = champ_select.asJsonObject["myTeam"].asJsonArray.map { it.asJsonObject["championId"].asInt }
		val champs = bench + my_team
		println(champs)
		current_champs = try {
			champs.map { LeagueData.champions_map[it]!! }
		} catch (e: Exception) {
			listOf()
		}
		println(current_champs)
		all_champ_table.sort()
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
		challenges.asJsonObject.entrySet().forEach { challenge ->
			val value = challenge.value.asJsonObject
			if (!value["completedIds"].asJsonArray.isEmpty && value["idListType"].asString == "CHAMPION"
				&& value["category"].asString != "COLLECTION"
				&& value["category"].asString != "LEGACY"
				&& !value["name"].asString.contains("Master")) {
				challenges_list.add(Challenge(challenge.key.toInt(), value["name"].asString, value["description"].asString, value["completedIds"].asJsonArray.map { it.asInt }))
			}
		}
	}

	private fun initialize_certificate() {
		val certificate_file = File("riotgames.pem")
		if (!certificate_file.exists()) {
			show_error("where is riotgames.pem?")
			exitProcess(1)
		}
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

	private fun put_endpoint(endpoint: String, data: String) {
		val url = "$prefix://127.0.0.1:$port/$endpoint"
		val connection = URI(url).toURL().openConnection() as HttpsURLConnection
		connection.sslSocketFactory = ssl_context.socketFactory

		// Set request method to PUT
		connection.requestMethod = "PUT"

		// Set request headers, if needed
		connection.setRequestProperty("Content-Type", "application/json")
		connection.setRequestProperty("Authorization", "Basic $auth")

		// Enable input/output
		connection.doInput = true
		connection.doOutput = true

		// Write the request body
		val outputStreamWriter = OutputStreamWriter(connection.outputStream)
		outputStreamWriter.write(data)
		outputStreamWriter.flush()

		// Get the response
		val responseCode = connection.responseCode
		val response = StringBuilder()
		val reader = BufferedReader(InputStreamReader(connection.inputStream))
		var line: String?
		while (reader.readLine().also { line = it } != null) {
			response.append(line)
		}
		reader.close()

		// Process the response
		println("Response Code: $responseCode")
		println("Response Body: $response")
	}

	private fun get_client_state(): String {
		val data = get_endpoint("lol-gameflow/v1/gameflow-phase")
		return data.asString
	}

	private fun init_1() {
		gameflow_phase.addListener { _, _, value ->
			status.text = value
			status.text += " " + current_champs.joinToString(", ", " (", ")")
		}
		val state = get_client_state()
		println(state)
		// invalidate to trigger it, not sure how else to do it
		gameflow_phase.set("asdf")
		gameflow_phase.set(state)
	}

	private fun initialize_lockfile() {
		val lockfile = File("C:\\Riot Games\\League of Legends\\lockfile")
		if (!lockfile.exists()) {
			show_error("Lockfile not found, is the game running?")
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
				//send("[5, \"OnJsonApiEvent\"]")
				send("[5, \"OnJsonApiEvent_lol-champ-select_v1_session\"]")
				send("[5, \"OnJsonApiEvent_lol-gameflow_v1_gameflow-phase\"]")
				while (true) {
					val inc = incoming.receive() as Frame.Text
					val text = inc.readText()
					print("text:")
					println(text)
					if (text == "") continue
					val json = JsonParser.parseString(text)
					val event_name = json.asJsonArray[1].asString
					val data = json.asJsonArray[2]
					when (event_name) {
						"OnJsonApiEvent_lol-champ-select_v1_session" -> {
							when (data.asJsonObject["eventType"].asString) {
								"Update", "Create" -> {
									update_champ_select()
								}
							}
						}
						"OnJsonApiEvent_lol-gameflow_v1_gameflow-phase" -> {
							val state = data.asJsonObject["data"].asString
							println(state)
							gameflow_phase.set(state)
						}
					}
				}
			}
		}
	}

	// for table rows
	data class Champion(val id: Int, val name: SimpleStringProperty, val mastery: SimpleIntegerProperty, val challenges: Map<Int, SimpleBooleanProperty>)
	// just to store data

	private fun initialize_ui() {
		all_champ_table.items = sorted_filtered
		sorted_filtered.comparatorProperty().bind(all_champ_table.comparatorProperty())
		val name_column = TableColumn<Champion, String>("Champion")
		name_column.setCellValueFactory {
			it.value.name
		}
		name_column.setComparator { o1, o2 ->
			val has1 = current_champs.contains(o1)
			val has2 = current_champs.contains(o2)
			if (has1 == has2) {
				o1.compareTo(o2)
			} else {
				if (has2) {
					1
				} else {
					-1
				}
			}
		}
		all_champ_table.sortOrder.add(name_column)
		val mastery_column = TableColumn<Champion, Int>("Mastery")
		mastery_column.setCellValueFactory {
			it.value.mastery.asObject()
		}
		all_champ_table.columns.addAll(name_column, mastery_column)

		challenges_list.forEach {
			val description = it.description
			val name = it.name
			val label = Label(name)
			label.tooltip = Tooltip(description)
			val column = TableColumn<Champion, Boolean>()
			column.prefWidth = name.length * 7.0
			column.text = ""
			column.graphic = label
			column.setCellValueFactory { cell ->
				cell.value.challenges[it.id]
			}
			all_champ_table.columns.add(column)
		}

		search.textProperty().addListener { _, _, value ->
			filtered_data.predicate = Predicate {
				it.name.value.contains(value, true)
			}
		}

		champ_select_table.visibleProperty().bind(gameflow_phase.isEqualTo("ChampSelect"))
		champ_select_table.managedProperty().bind(champ_select_table.visibleProperty())
	}

	private fun update_ui() {
		data.clear()
		LeagueData.champions_map.forEach { champion ->
			val name = champion.value
			val id = champion.key
			val mastery = masteries_map[id] ?: 0
			val challenges = mutableMapOf<Int, SimpleBooleanProperty>()
			challenges_list.forEach { challenge ->
				val challenge_id = challenge.id
				val completed = challenge.completion.contains(id)
				challenges[challenge_id] = SimpleBooleanProperty(completed)
			}

			data.add(Champion(id, SimpleStringProperty(name), SimpleIntegerProperty(mastery), challenges))
		}
	}

	fun set_status_message(message: String) {
		put_endpoint("lol-chat/v1/me", "{\"statusMessage\": \"$message\"}")
	}

	fun set_status(actionEvent: ActionEvent) {
		set_status_message(message.text)
	}

	fun initialize() {
		initialize_lockfile()
		initialize_certificate()
		println(get_endpoint("help"))
		initialize_websocket_listener()
		init_1()
		init_summoner()
		LeagueData.init_champions()
		update_masteries()
		update_challenges()
		initialize_ui()
		update_ui()
	}

	fun refresh_challenges(actionEvent: ActionEvent) {
		update_challenges()
		update_ui()
	}
}