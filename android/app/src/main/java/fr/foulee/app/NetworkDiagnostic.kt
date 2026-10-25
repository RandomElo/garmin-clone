package fr.foulee.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.random.Random

/** One line of the report: [ok] true (works), false (broken), null (to know, not a verdict). */
class DiagLine(val ok: Boolean?, val title: String, val detail: String)

class DiagReport(val lines: List<DiagLine>, val conclusion: String, val good: Boolean)

/**
 * Why the phone cannot reach the home Strava server, step by step: which network is used, which DNS servers it gives, whether a private DNS
 * bypasses them, what the system resolver says, what each DNS server answers when asked directly, and whether the server then answers.
 * It only reads and asks, it changes no setting.
 */
object NetworkDiagnostic {
    private class DnsAnswer(val outcome: String, val addresses: List<String>)

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    /** Asks one DNS server directly (UDP, port 53) for the A records of [name], on [network] when given. */
    private fun ask(server: InetAddress, name: String, network: Network?): DnsAnswer = try {
        val id = Random.nextInt(65536)
        val q = ByteArrayOutputStream()
        q.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.split('.')) {
            q.write(label.length)
            q.write(label.toByteArray())
        }
        q.write(byteArrayOf(0, 0, 1, 0, 1))
        DatagramSocket().use { socket ->
            network?.bindSocket(socket)
            socket.soTimeout = 3000
            val bytes = q.toByteArray()
            socket.send(DatagramPacket(bytes, bytes.size, server, 53))
            val buffer = ByteArray(1500)
            val reply = DatagramPacket(buffer, buffer.size)
            socket.receive(reply)
            if (reply.length < 12 || u16(buffer, 0) != id) DnsAnswer("réponse invalide", emptyList())
            else {
                val rcode = buffer[3].toInt() and 0x0F
                val answers = u16(buffer, 6)
                // Skips the question, then reads the A records (type 1, four bytes)
                fun skipName(from: Int): Int {
                    var p = from
                    while (p < reply.length) {
                        val len = buffer[p].toInt() and 0xFF
                        if (len == 0) return p + 1
                        if (len and 0xC0 == 0xC0) return p + 2
                        p += len + 1
                    }
                    return p
                }
                var pos = skipName(12) + 4
                val found = mutableListOf<String>()
                repeat(answers) {
                    if (pos + 10 > reply.length) return@repeat
                    pos = skipName(pos)
                    val type = u16(buffer, pos)
                    val length = u16(buffer, pos + 8)
                    pos += 10
                    if (type == 1 && length == 4 && pos + 4 <= reply.length) found += (0 until 4).joinToString(".") { (buffer[pos + it].toInt() and 0xFF).toString() }
                    pos += length
                }
                when {
                    rcode == 3 -> DnsAnswer("ne connaît pas ce nom", emptyList())
                    rcode != 0 -> DnsAnswer("erreur DNS $rcode", emptyList())
                    found.isEmpty() -> DnsAnswer("répond sans adresse", emptyList())
                    else -> DnsAnswer("connaît ce nom", found)
                }
            }
        }
    } catch (e: java.net.SocketTimeoutException) {
        DnsAnswer("ne répond pas", emptyList())
    } catch (e: Exception) {
        DnsAnswer("injoignable (${e.message ?: e.javaClass.simpleName})", emptyList())
    }

    fun run(context: Context, address: String?): DiagReport {
        val url = StravaSync.normalize(address) ?: "http://strava.home"
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        val host = uri?.host ?: "strava.home"
        val port = if (uri != null && uri.port != -1) uri.port else if (uri?.scheme == "https") 443 else 80
        val lines = mutableListOf<DiagLine>()

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val link = network?.let { cm.getLinkProperties(it) }
        if (network == null || caps == null) {
            lines += DiagLine(false, "Réseau", "Le téléphone n'est connecté à aucun réseau.")
            return DiagReport(lines, "Pas de réseau : connecte le téléphone au Wi-Fi de la maison, ou active le VPN.", false)
        }
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val kind = listOfNotNull(if (wifi) "Wi-Fi" else null, if (cellular) "données mobiles" else null, if (vpn) "VPN" else null).joinToString(" + ").ifEmpty { "autre" }
        lines += DiagLine(
            if (cellular && !wifi && !vpn) false else true, "Réseau utilisé par l'appli",
            kind + if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) ", avec accès à Internet" else ", sans accès à Internet confirmé",
        )

        val addresses = link?.linkAddresses?.map { it.address.hostAddress ?: "" }?.filter { it.isNotEmpty() && !it.contains(':') } ?: emptyList()
        val gateway = link?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway?.hostAddress
        lines += DiagLine(null, "Adresse du téléphone", (addresses.joinToString(", ").ifEmpty { "inconnue" }) + (gateway?.let { " · passerelle $it" } ?: ""))

        val dns = link?.dnsServers?.filter { it.hostAddress != null } ?: emptyList()
        lines += DiagLine(if (dns.isEmpty()) false else null, "Serveurs DNS donnés par ce réseau", if (dns.isEmpty()) "aucun" else dns.joinToString(", ") { it.hostAddress ?: "" })

        val privateDns = link?.isPrivateDnsActive == true
        val privateName = link?.privateDnsServerName
        lines += DiagLine(
            if (privateDns) false else true, "DNS privé d'Android",
            if (!privateDns) "inactif" else "actif" + (privateName?.let { " (serveur « $it »)" } ?: " (détection automatique)") + " : les requêtes passent par un service chiffré qui ne connaît pas les noms de ton réseau",
        )

        // What the system resolver says, as the app itself will see it
        val started = System.currentTimeMillis()
        var systemResolved: List<String> = emptyList()
        val systemError = try {
            systemResolved = network.getAllByName(host).mapNotNull { it.hostAddress }
            null
        } catch (e: Exception) {
            e.javaClass.simpleName
        }
        lines += if (systemError == null) DiagLine(true, "Résolution de « $host » par le système", "${systemResolved.joinToString(", ")} (${System.currentTimeMillis() - started} ms)")
        else DiagLine(false, "Résolution de « $host » par le système", "échec ($systemError)")

        // What each DNS server answers when asked directly
        val answers = dns.map { it to ask(it, host, network) }
        answers.forEach { (server, answer) ->
            lines += DiagLine(
                if (answer.outcome == "connaît ce nom") true else false, "DNS ${server.hostAddress} interrogé directement",
                answer.outcome + if (answer.addresses.isNotEmpty()) " : ${answer.addresses.joinToString(", ")}" else "",
            )
        }

        // A DNS server silent over UDP: does its port accept a TCP connection? (service down or firewall, versus a server that ignores this phone)
        answers.filter { it.second.outcome == "ne répond pas" && it.first is java.net.Inet4Address }.forEach { (server, _) ->
            val open = try {
                Socket().use { sk ->
                    network.bindSocket(sk)
                    sk.connect(InetSocketAddress(server, 53), 3000)
                }
                true
            } catch (e: Exception) {
                false
            }
            lines += DiagLine(open, "Port 53 (TCP) de ${server.hostAddress}", if (open) "accepte la connexion : le service DNS tourne, il ignore ce téléphone en UDP" else "refusé ou sans réponse : service DNS arrêté ou pare-feu")
        }

        // Whether the server answers once the address is known
        val target = systemResolved.firstOrNull() ?: answers.firstNotNullOfOrNull { it.second.addresses.firstOrNull() }
        var tcpOk: Boolean? = null
        var httpOk: Boolean? = null
        if (target != null) {
            tcpOk = try {
                Socket().use { s ->
                    network.bindSocket(s)
                    s.connect(InetSocketAddress(target, port), 4000)
                }
                true
            } catch (e: Exception) {
                false
            }
            lines += DiagLine(tcpOk, "Connexion à $target:$port", if (tcpOk) "le serveur accepte la connexion" else "refusée ou sans réponse : serveur éteint, mauvais port ou pare-feu")
            // By name, like the app does: a call by address would be refused by Android's clear text rule, which only knows the name
            if (tcpOk && systemError == null) {
                httpOk = try {
                    val connection = network.openConnection(URL("${if (uri?.scheme == "https") "https" else "http"}://$host:$port/api/activites")) as HttpURLConnection
                    connection.connectTimeout = 4000
                    connection.readTimeout = 4000
                    // A 401 without a key is the good answer: the server is there and wants its key
                    val code = connection.responseCode
                    connection.disconnect()
                    lines += DiagLine(code in 200..499, "Réponse du serveur", "HTTP $code" + if (code == 401) " (normal sans clé)" else "")
                    code in 200..499
                } catch (e: Exception) {
                    lines += DiagLine(false, "Réponse du serveur", "échec (${e.message ?: e.javaClass.simpleName})")
                    false
                }
            }
        }

        val knowing = answers.filter { it.second.outcome == "connaît ce nom" }.map { it.first.hostAddress }
        val ignorant = answers.filter { it.second.outcome == "ne connaît pas ce nom" }.map { it.first.hostAddress }
        // A local DNS server that stays silent while a public one answers: the phone falls back on the public one, which does not know the name
        val silent = answers.filter { it.second.outcome == "ne répond pas" && it.first is java.net.Inet4Address }.map { it.first.hostAddress }
        val silentLocal = silent.filter { a -> a != null && (a.startsWith("192.168.") || a.startsWith("10.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(a)) }
        val conclusion = when {
            cellular && !wifi && !vpn -> "Le téléphone utilise les données mobiles : « $host » n'existe que sur le réseau de la maison. Connecte le Wi-Fi de la maison ou le VPN."
            systemError != null && privateDns && answers.isNotEmpty() && knowing.isNotEmpty() ->
                "Le DNS ${knowing.first()} connaît « $host », mais le DNS privé d'Android l'écarte. Règle le DNS privé sur Désactivé (Réglages, Connexions, Plus de paramètres de connexion)."
            systemError != null && silentLocal.isNotEmpty() && (ignorant.isNotEmpty() || knowing.isNotEmpty()) ->
                "Le téléphone n'obtient aucune réponse du DNS ${silentLocal.first()}, alors que ${(ignorant + knowing).first()} répond : Android se rabat donc sur un DNS qui ne connaît pas « $host ». " +
                    "Deux cas : la requête n'arrive pas au serveur, ou sa réponse ne revient pas. Pour le savoir, ouvre tout de suite le journal des requêtes d'AdGuard Home (filtre « Tous les statuts », client ${addresses.firstOrNull() ?: "du téléphone"}) : " +
                    "si la requête y est, c'est la réponse qui se perd (pare-feu, routeur ou Wi-Fi entre le serveur et le téléphone) ; sinon c'est la requête qui n'arrive pas."
            systemError != null && knowing.isNotEmpty() && ignorant.isNotEmpty() ->
                "Le DNS ${knowing.first()} connaît « $host », mais ${ignorant.joinToString(", ")} non, et le téléphone l'interroge aussi. Retire ${ignorant.joinToString(", ")} des réglages du Wi-Fi, ou remplace-le par ${knowing.first()}."
            systemError != null && knowing.isNotEmpty() -> "Les DNS connaissent « $host » quand on les interroge directement, mais pas le système : relance le Wi-Fi (coupe puis rallume) et reteste."
            systemError != null && answers.isNotEmpty() && ignorant.size == answers.size ->
                "Aucun DNS du réseau ne connaît « $host » : l'entrée DNS n'existe pas pour ce réseau. Vérifie la réécriture DNS dans AdGuard et que le Wi-Fi utilise bien ce DNS."
            systemError != null && dns.isNotEmpty() && answers.all { it.second.outcome == "ne répond pas" } ->
                "Aucun DNS du réseau ne répond depuis le téléphone : serveur DNS éteint, ou le téléphone n'est pas sur le bon réseau."
            systemError != null -> "Le système ne résout pas « $host ». Regarde les lignes ci-dessus pour voir quel DNS manque."
            tcpOk == false -> "« $host » est trouvé ($target) mais ne répond pas sur le port $port : serveur éteint, ou pare-feu."
            httpOk == false -> "Le serveur est joint mais ne répond pas comme prévu : regarde son état sur la Raspberry."
            else -> "Tout est bon : le téléphone trouve et joint « $host ». Si l'appli échoue encore, enregistre l'adresse et teste de nouveau."
        }
        val good = systemError == null && tcpOk != false && httpOk != false
        return DiagReport(lines, conclusion, good)
    }
}
