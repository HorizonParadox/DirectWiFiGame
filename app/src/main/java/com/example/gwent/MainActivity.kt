package com.example.gwent

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ConnectionInfoListener
import android.net.wifi.p2p.WifiP2pManager.PeerListListener
import android.os.Bundle
import android.os.Handler
import android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.android.synthetic.main.main_activity.*
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.doAsyncResult
import org.jetbrains.anko.uiThread
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.StringBuilder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class MainActivity : AppCompatActivity() {
    // Этот класс предоставляет основной API для управления всеми аспектами подключения Wi-Fi
    private lateinit var wifiManager: WifiManager
    // Этот класс предоставляет API для управления одноранговой связью Wi-Fi
    private lateinit var mManager: WifiP2pManager
    // Канал, который соединяет приложение с платформой Wifi p2p.
    private lateinit var mChannel: WifiP2pManager.Channel
    // Базовый класс для кода, который получает и обрабатывает широковещательные намерения
    private lateinit var mReceiver: BroadcastReceiver
    // Структурированное описание значений Intent для сопоставления
    private lateinit var mIntentFilter: IntentFilter

    // Изменяемые коллекции, необходимые для вывода на экран всех доступных для соединения устройств
    private val peers: MutableList<WifiP2pDevice> = mutableListOf()
    private val deviceNameArray : MutableList<String> = mutableListOf()
    private val deviceArray : MutableList<WifiP2pDevice> = mutableListOf()

    // Переменные для запуска классов клиента, сервера и передачи сообщений
    private lateinit var serverClass: ServerClass
    private lateinit var clientClass: ClientClass
    private lateinit var sendReceive: SendReceive

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        initialization()
        wifiSelector()
    }

    /**
 * Запрос на доступ к данным местоположения устройства, на включение WiFi соединения,
 *  а также инициализация основных компонентов в основном классе MainActivity
 *  */
    private fun initialization(){

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CHANGE_WIFI_STATE), 1)

        val lm: LocationManager =
            applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled: Boolean = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled: Boolean = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!gpsEnabled && !networkEnabled) {
            startActivity(Intent(ACTION_LOCATION_SOURCE_SETTINGS))
        }
    } else {
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.CHANGE_WIFI_STATE), 1)
    }

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        mManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        mChannel = mManager.initialize(this, mainLooper, null)

        mReceiver = WifiDirectBroadcastReceiver(mManager, mChannel, this)
        mIntentFilter = IntentFilter()
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    /**
     * Вывод на экран тех устройств, которые находятся в поиске на сопряжение
     * Используется обычный listView
     */
    var peerListListener = PeerListListener { peerList ->
            if (peerList.deviceList != peers) {
                peers.clear()
                peers.addAll(peerList.deviceList)
                for (device in peerList.deviceList) {
                    deviceNameArray.add(device.deviceName)
                    deviceArray.add(device)
                }
                val adapter = ArrayAdapter(
                    applicationContext, android.R.layout.simple_list_item_1, deviceNameArray)
                mListView.adapter = adapter
            }
            if (peers.isEmpty()) {
                Toast.makeText(applicationContext, "No Device Found", Toast.LENGTH_SHORT).show()
            }
        }

    /**
     * Вывод на экран состояние подключения: Кто из устройств является хостом, а кто клиентом
     * */
    var connectionInfoListener = ConnectionInfoListener { wifiP2pInfo ->
            val groupOwnerAddress = wifiP2pInfo.groupOwnerAddress
            if (wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner) {
                txtConnect.text = getString(R.string.host)
                serverClass = ServerClass()
                serverClass.start()
            } else if (wifiP2pInfo.groupFormed) {
                txtConnect.text = getString(R.string.client)
                clientClass = ClientClass(groupOwnerAddress)
                clientClass.start()
            }
        }

    override fun onResume() {
        super.onResume()
        registerReceiver(mReceiver, mIntentFilter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(mReceiver)
    }

    /**
     * Обработчик всех кнопок в пользовательском интерфейсе
     * */
    private fun wifiSelector() {
        /** Включение/Выключение WiFi на устройстве */
        btnWiFiSelector.setOnClickListener {
            if (wifiManager.isWifiEnabled) {
                wifiManager.isWifiEnabled = false
                btnWiFiSelector.text = getString(R.string.wifi_on)
            } else {
                wifiManager.isWifiEnabled = true
                btnWiFiSelector.text = getString(R.string.wifi_off)
            }
        }

        /**Поиск доступных для соединения устройств*/
        btnDiscover.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED){
                    return@setOnClickListener
            }
            mManager.discoverPeers(mChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    txtConnect.text = getString(R.string.discovery_started)
                }

                override fun onFailure(p0: Int) {
                    txtConnect.text = getString(R.string.discovery_starting_failed)
                }
            })
        }

        /**Подключение к выбранному из списка устройству*/
        mListView.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, i, _ ->
                    val device: WifiP2pDevice? = deviceArray[i]
                    val config = WifiP2pConfig()
                    config.deviceAddress = device?.deviceAddress
                mManager.connect(mChannel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Toast.makeText(applicationContext, "Connected to " + device?.deviceName,
                            Toast.LENGTH_SHORT).show()
                    }

                    override fun onFailure(p0: Int) {
                        Toast.makeText(applicationContext, "Not Connected", Toast.LENGTH_SHORT)
                            .show()
                    }
                })
            }

        /**Отправка сообщения по нажатию кнопки Send*/
        btnSend.setOnClickListener {
            doAsync {
                val msg: String = editTxt.text.toString()
                sendReceive.write(msg.toByteArray())
            }
        }
    }

    /**
     * Обработчик серверного устройства: подключение к сокету через порт, передача сокета в
     * SendReceive, в котором происходит передача сообщений, запуск сокета
     * */
    inner class ServerClass : Thread() {
        private lateinit var  mSocket: Socket
        private lateinit var serverSocket: ServerSocket

        override fun run() {
            try {
                serverSocket = ServerSocket(8888)
                mSocket = serverSocket.accept()
                sendReceive = SendReceive(mSocket)
               sendReceive.start()
            } catch (e: IOException){
                e.printStackTrace()
            }
        }
    }


    val handler = Handler { message ->
        when (message.what) {
            MESSAGE_READ -> {
                    val readBuff = message.obj as ByteArray
                    var tempMsgStr = ""
                    tempMsg.add(String(readBuff, 0, message.arg1))
                    for (str in tempMsg)
                        tempMsgStr = str
                        txtMessage.text = tempMsgStr
            }
        }
        true
    }


    /**
     * Выводит на экран сообщение, отправленное между устройствами
     * */


    /**
     * Класс, который занимается отправкой сообщения и его приёмом через inputStream и outputStream
     * */
    inner class SendReceive(skt: Socket) : Thread(){
        private var mInputStream: InputStream = skt.getInputStream()
        private  var mOutputStream: OutputStream = skt.getOutputStream()

        override fun run() {

            val buffer = ByteArray(1024)
            var bytes: Int
            while (true){
                try {
                    bytes = mInputStream.read(buffer)
                    if (bytes > 0) {
                        handler.obtainMessage(MESSAGE_READ, bytes, -1, buffer).sendToTarget()
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        fun write(bytes: ByteArray) {
            try {
                    mOutputStream.write(bytes)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Подключение клиентского устройства к хосту через сокет(порт 8888). Запускает класс отправки
     * и приёма сообщений
     * */
    inner class ClientClass(hostAddress: InetAddress): Thread(){
        private var mSocket: Socket = Socket()
        private var hostAdd: String = hostAddress.hostAddress

        override fun run() {
            try {
                mSocket.connect(InetSocketAddress(hostAdd, 8888), 500)
                sendReceive = SendReceive(mSocket)
                sendReceive.start()
            } catch (e: IOException){
                e.printStackTrace()
            }
        }
    }

    companion object {
        const val  MESSAGE_READ = 1
        val tempMsg: MutableList<String> = mutableListOf()
    }

}