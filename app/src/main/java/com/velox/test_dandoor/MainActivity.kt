package com.velox.test_dandoor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.ResponseBody
import retrofit2.Callback
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


// 서버 전송 데이터
data class LocationData(
    val deviceId: String,   // RC 카 식별자
    val anchorName: String,  // 앵커 이름
    val rssi: Int,           // 신호 강도
    val macAddress: String,   // 장치 물리 주소
    val timestamp: Long       // 이벤트 발생 시간
)

// API 서비스 인터페이스
interface ApiService {
    companion object {
        const val BASE_URL = "test" // 소형 서버 IP
    }

    @POST("location/rssi")  // 서버 엔드포인트
    fun sendLocationData(@Body data: LocationData): retrofit2.Call<ResponseBody>
}

class MainActivity : ComponentActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private var scanning = false

    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView

    // 핸들러 ( 메인 스레드 제어 )
    private val handler = Handler(Looper.getMainLooper())

    // 필요 권한 정보
    private val permissionArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            initializeBluetooth()
        } else {
            Toast.makeText(this, "권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }


    // 앵커 정보 ( 이름 -> 좌표 매핑 )
    private val anchorDevices = mapOf(
        "Anchor1" to Pair(0.0, 0.0),
        "Anchor2" to Pair(10.0, 0.0),
        "Anchor3" to Pair(5.0, 8.66)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // logTextView, scrollView 초기화
        logTextView = findViewById(R.id.logTextView)
        scrollView = findViewById(R.id.scrollView)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        if (permissionArray.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            // 이미 권한이 있으면 바로 BLE 초기화
            initializeBluetooth()
        } else {
            // 권한이 없으면 런처로 요청
            requestPermissionLauncher.launch(permissionArray)
        }
    }

    // BLE 초기화 함수
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // 블루투스 활성화 성공
            bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            startPeriodScan()
        } else {
            // 블루투스 활성화 거부
            Toast.makeText(this, "블루투스 활성화 필요", Toast.LENGTH_SHORT).show()
        }
    }
    private fun initializeBluetooth() {
        // 1. 시스템 블루투스 서비스 가져오기
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // 2. 블루투스 지원여부 확인
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "블루투스 미지원", Toast.LENGTH_SHORT).show()
            return
        }

        // 3. 블루투스 활성화 여부 확인 및 요청
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED) {
                // 권한이 있으면 블루투스 활성화 요청 화면 띄우기
                enableBtLauncher.launch(enableBtIntent)
            }
        } else {
            // 이미 활성화되어 있으면 BLE 스캐너 초기화 및 주기적 스캔 시작
            bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            startPeriodScan()
        }
    }
    private fun startPeriodScan() {
        val scanRunnable = object: Runnable {
            override fun run() {
                startScan()
                handler.postDelayed(this, 6000)
            }
        }
        handler.post(scanRunnable)
    }

    // BLE 콜백 함수
    private val scanCallback = object : ScanCallback() {

        // BLE 장치가 감지될 때마다 호출
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            // 1. 권한 확인
            if (ActivityCompat.checkSelfPermission(
                    /* context = */ this@MainActivity,
                    /* permission = */ Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED) return

            // 2. 스캔 결과 처리
            result?.let { scanResult ->
                // 3. 장치 정보 추출
                val deviceName = scanResult.device.name ?: "Unknown"
                val rssi = scanResult.rssi
                val macAddress = scanResult.device.address

                // 4. nRF5349-DK 앵커 식별(미구현)
                // 5. 서버 전송
                sendRssiToServer(deviceName, rssi, macAddress)
            }
        }

        // 스캔 실패 시 오류 코드 반환
        override fun onScanFailed(errorCode: Int) {
            // SCAN_FAILED_ALREADY_STARTED                  (1): 이미 스캔 중
            // SCAN_FAILED_APPLICATION_REGISTRATION_FAILED  (2): 앱 등록 실패
            // SCAN_FAILED_INTERNAL_ERROR                   (3): 내부 오류
            // SCAN_FAILED_FEATURE_UNSUPPORTED              (4): 기기 미지원
            Log.e("BLE_SCAN", "스캔 실패, $errorCode")
        }
    }
    // BLE 스캔 시작&종료
    private fun startScan() {
        // 1. BLE 지원 여부 확인
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "BLE 미지원", Toast.LENGTH_SHORT).show()
            return
        }

        // 2. 스캔 설정(스캔 모드: 저전력)
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        // 3. 스캔 필터 ( 특정 장치만 스캔하고 싶을 때 사용, 여기선 빈 리스트)
        val scanFilters = listOf<ScanFilter>()

        // 4. 권한 확인
        if (ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED) {
            // 5. 스캔 시작
            bluetoothLeScanner.startScan(scanFilters, scanSettings, scanCallback)
            scanning = true

            // 6. 10초 후 자동 중지 (핸들러 사용)
            handler.postDelayed({ stopScan() }, 6000)
        } else {
            // 권한이 없으면 권한 요청
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_SCAN),
                1
            )
        }
    }
    private fun stopScan() {
        // 1. 스캔 중이고 권한이 있으면
        if (scanning && ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED) {
            // 2. 스캔 중지
            bluetoothLeScanner.stopScan(scanCallback)
            scanning = false
        }
    }

    // 서버 전송 함수
    private fun sendRssiToServer(anchorName: String, rssi: Int, macAddress: String) {
        if (ApiService.BASE_URL != "test"){// 1. Retrofit 인스턴스 생성
            val retrofit = Retrofit.Builder()
                .baseUrl(ApiService.BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            // 2. API 서비스 인터페이스 구현체 생성
            val apiService = retrofit.create(ApiService::class.java)

            // 3. 전송할 데이터 객체 생성
            val locationdata = LocationData(
                deviceId = "RC_CAR_001",      // 고정된 장치 ID
                anchorName = anchorName,       // 앵커 이름 (예: "Anchor1")
                rssi = rssi,                  // 신호 강도 (-30 ~ -100 dBm)
                macAddress = macAddress,       // 장치 MAC 주소
                timestamp = System.currentTimeMillis()  // 현재 시간(밀리초)
            )

            // 4. 비동기 네트워크 요청 실행
            apiService.sendLocationData(locationdata).enqueue(object : Callback<ResponseBody> {
                // 5. 서버 응답 처리
                override fun onResponse(
                    call: retrofit2.Call<ResponseBody>,
                    response: retrofit2.Response<ResponseBody>
                ) {
                    if (response.isSuccessful) {
                        Log.d("SERVER", "데이터 전송 성공: $anchorName, RSSI: $rssi")
                    } else {
                        Log.d("SERVER", "전송 실패: ${response.code()}")
                    }
                }

                // 6. 네트워크 실패 처리
                override fun onFailure(call: retrofit2.Call<ResponseBody>, t: Throwable) {
                    Log.e("SERVER", "네트워크 오류: ${t.message}")
                }
            })
        } else {
            updateLog("서버 통신 중... ${anchorName}, ${rssi}, ${macAddress}")
        }
    }
    
    // 로그 메시지를 TextView에 추가하는 함수
    private fun updateLog(message: String) {
        runOnUiThread {
            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val logMessage = "[$currentTime] $message\n"
            logTextView.append(logMessage)

            // 스크롤뷰를 최하단으로 이동
            val scrollView = findViewById<ScrollView>(R.id.scrollView)
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }
}