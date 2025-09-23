package com.devx.zebra_rfid_plugin

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import com.zebra.rfid.api3.*
import com.zebra.scannercontrol.DCSSDKDefs
import com.zebra.scannercontrol.DCSScannerInfo
import com.zebra.scannercontrol.FirmwareUpdateEvent
import com.zebra.scannercontrol.IDcsSdkApiDelegate
import com.zebra.scannercontrol.SDKHandler
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ZebraRfidPlugin :
  FlutterPlugin,
  MethodChannel.MethodCallHandler,
  ActivityAware,
  IDcsSdkApiDelegate {

  // ---- Flutter/Android
  private lateinit var channel: MethodChannel
  private lateinit var appContext: Context
  private var activity: Activity? = null
  @Volatile private var isActivityAttached = false

  // Fallback lifecycle
  private var lifecycleRegistered = false

  private fun isHostFlutterActivity(a: Activity): Boolean {
    return a.javaClass.name == "${appContext.packageName}.MainActivity"
  }

  private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(a: Activity) {
      if (!isHostFlutterActivity(a)) {
        Log.d(TAG, "Ignoring non-host resume: ${a.javaClass.name}")
        return
      }
      activity = a
      isActivityAttached = true
      Log.d(TAG, "Host activity (fallback) attached: ${a.localClassName}")

      pendingInitResult?.let { r ->
        pendingInitResult = null
        postMain { init(r) }
      }
      postMain { settlePendingPermissionIfAny() }
    }
    override fun onActivityDestroyed(a: Activity) {
      if (!isHostFlutterActivity(a)) return
      if (activity === a) {
        activity = null
        isActivityAttached = false
        Log.d(TAG, "Host activity (fallback) destroyed")
      }
    }
    override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
  }

  // ---- Zebra RFID API3
  private var readers: Readers? = null
  private var readerDevice: ReaderDevice? = null
  private var reader: RFIDReader? = null
  private var rfidListener: RfidEventsListener? = null

  // Preferred reader selection (set from Dart via connectReader)
  private var preferredReaderAddress: String? = null
  private var preferredReaderName: String? = null

  // ---- Scanner Control SDK (SCS) for sled barcode scanning ----
  private var sdkHandler: SDKHandler? = null
  private var activeScannerId: Int = -1
  @Volatile private var isSCSReady = false

  // ---- Runtime state flags ----
  @Volatile private var isActive = false
  @Volatile private var isInventoryRunning = false
  @Volatile private var isTriggerHeld = false

  // ---- Pending results for async operations ----
  private var pendingInitResult: MethodChannel.Result? = null
  private var pendingPermissionResult: MethodChannel.Result? = null
  @Volatile private var permissionRequestedByInit = false
  private var lastRequestedPerms: Array<String>? = null

  // ---- Initialization guard ----
  @Volatile private var initInProgress = false

  // ---- SharedPreferences: track if a permission has ever been requested ----
  private val PREFS = "zebra_rfid_plugin_prefs"
  private fun prefs(): SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  private fun hasBeenAsked(perm: String) = prefs().getBoolean("asked_$perm", false)
  private fun markAsked(perms: Array<String>?) {
    if (perms == null) return
    val e = prefs().edit()
    perms.forEach { e.putBoolean("asked_$it", true) }
    e.apply()
  }

  // ---- Anti-blocking: handle OEMs that do not return permission callbacks ----
  @Volatile private var pendingPermRequestEpoch: Long = 0L
  @Volatile private var pendingPermsInFlight: Boolean = false

  private val TAG = "ZebraRfidPlugin"
  private val REQ_CODE = 1001

  //region FlutterPlugin
  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    appContext = binding.applicationContext
    channel = MethodChannel(binding.binaryMessenger, "zebra_rfid_plugin")
    channel.setMethodCallHandler(this)

    (appContext.applicationContext as? Application)?.let { app ->
      if (!lifecycleRegistered) {
        app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        lifecycleRegistered = true
      }
    }

    runOnMainAndWait { ensureSCSHandlerOnMain() }
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
    cleanupAll()
    (appContext.applicationContext as? Application)?.let { app ->
      if (lifecycleRegistered) {
        app.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        lifecycleRegistered = false
      }
    }
  }
  //endregion

  //region ActivityAware
  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity
    isActivityAttached = true
    Log.d(TAG, "onAttachedToActivity: ${activity?.localClassName}")

    binding.addRequestPermissionsResultListener { requestCode, _, _ ->
      if (requestCode == REQ_CODE && pendingPermissionResult != null) {
        pendingPermsInFlight = false
        pendingPermRequestEpoch = 0L

        val r = pendingPermissionResult
        pendingPermissionResult = null
        val fromInit = permissionRequestedByInit
        permissionRequestedByInit = false

        markAsked(lastRequestedPerms)
        lastRequestedPerms = null

        val allGranted = hasAllRequiredPermissions()
        if (allGranted) {
          if (fromInit && r != null) {
            postMain { initializeAll(r) }
          } else {
            r?.success(true)
          }
        } else {
          val missing = missingPermissions()
          val permanentlyDenied = isPermanentlyDeniedNow(missing)
          val code = if (permanentlyDenied) "PERMISSION_PERMANENTLY_DENIED" else "PERMISSION_DENIED"
          val msg = if (permanentlyDenied)
            "Permissions denied with 'Don't ask again'. Open app settings to grant permissions."
          else
            "Bluetooth/Location permissions denied"
          r?.error(code, msg, null)
        }
        true
      } else false
    }

    pendingInitResult?.let { res ->
      pendingInitResult = null
      postMain { init(res) }
    }

    postMain { settlePendingPermissionIfAny() }
  }

  override fun onDetachedFromActivity() {
    Log.d(TAG, "onDetachedFromActivity")
    isActivityAttached = false
    activity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    onAttachedToActivity(binding)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    Log.d(TAG, "onDetachedFromActivityForConfigChanges")
    isActivityAttached = false
    activity = null
  }
  //endregion

  //region MethodChannel
  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    when (call.method) {
      "init" -> init(result)

      "listReaders" -> {
        try { result.success(listAvailableReaders()) }
        catch (e: Exception) { result.error("LIST_READERS_FAILED", e.localizedMessage, null) }
      }

      "connectReader" -> {
        val args = call.arguments as? Map<*, *>
        preferredReaderAddress = (args?.get("address") as? String)?.normalizeBtAddr()
        preferredReaderName = args?.get("name") as? String
        init(result) // do init with preferences
      }

      "getReaderName" -> {
        val name = reader?.hostName ?: readerDevice?.name
        result.success(name ?: "RFID Reader")
      }

      "setMode" -> {
        try {
          val mode = (call.arguments as? String)?.trim()?.uppercase() ?: "OFF"
          setModeInternal(mode)
          result.success(currentMode)
        } catch (e: Exception) {
          result.error("SET_MODE_FAILED", e.localizedMessage, null)
        }
      }

      "getMode" -> result.success(currentMode)

      "start" -> {
        try {
          if (currentMode == "OFF") {
            result.error("MODE_OFF", "Select a mode before starting", null); return
          }
          if (currentMode != "BARCODE" && reader?.isConnected != true) {
            result.error("NOT_CONNECTED", "RFID reader not connected", null); return
          }
          isActive = true

          if (currentMode == "BARCODE" || currentMode == "BARCODE_RFID") {
            runOnMainAndWait { ensureSCSHandlerOnMain(); scsEnsureSessionOnMain() }
          }

          applyRoutingForCurrentMode()
          result.success(true)
        } catch (e: Exception) {
          result.error("START_FAILED", e.localizedMessage, null)
        }
      }

      "stop" -> {
        try {
          isActive = false
          isTriggerHeld = false
          stopInventorySafe(reader)
          runOnMainAndWait { scsTerminateSessionOnMain() }
          result.success(true)
        } catch (e: Exception) {
          result.error("STOP_FAILED", e.localizedMessage, null)
        }
      }

      "setPower" -> {
        val level = call.arguments as? Int ?: -1
        val r = reader
        if (r?.isConnected == true) {
          try {
            if (level !in 0..300) throw IllegalArgumentException("Power index out of range (0–300): $level")
            val cfg = r.Config.Antennas.getAntennaConfig(1)
            cfg.transmitPowerIndex = level.toShort()
            r.Config.Antennas.setAntennaConfig(1, cfg)
            result.success(true)
          } catch (e: Exception) {
            result.error("SET_POWER_FAILED", e.localizedMessage, null)
          }
        } else result.error("NOT_CONNECTED", "RFID reader not connected", null)
      }

      "getPlatformVersion" -> result.success("Android ${Build.VERSION.RELEASE}")

      "disconnect" -> {
        try {
          isActive = false
          isTriggerHeld = false
          cleanupAll()
          result.success(true)
        } catch (e: Exception) {
          result.error("DISCONNECT_FAILED", e.localizedMessage, null)
        }
      }

      "dispose" -> {
        isActive = false
        isTriggerHeld = false
        cleanupAll()
        result.success(true)
      }

      else -> result.notImplemented()
    }
  }
  //endregion

  //region Init & permissions
  private fun missingPermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      listOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION
      ).filter { ActivityCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED }
    } else {
      listOf(Manifest.permission.ACCESS_FINE_LOCATION).filter {
        ActivityCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED
      }
    }
  }

  private fun isPermanentlyDeniedNow(perms: List<String>): Boolean {
    val host = activity ?: return false
    return perms.any { p ->
      hasBeenAsked(p) &&
        ActivityCompat.checkSelfPermission(appContext, p) != PackageManager.PERMISSION_GRANTED &&
        !ActivityCompat.shouldShowRequestPermissionRationale(host, p)
    }
  }

  private fun hasAllRequiredPermissions() = missingPermissions().isEmpty()

  private fun requestMissingPermissionsIfPossible(
    result: MethodChannel.Result,
    fromInit: Boolean
  ): Boolean {
    val host = activity
    val missing = missingPermissions()
    if (missing.isEmpty()) return false

    if (host == null) {
      result.error("ACTIVITY_REQUIRED_FOR_PERMISSION", "Cannot request permissions: Activity not attached.", null)
      return true
    }

    if (pendingPermissionResult != null || pendingPermsInFlight) {
      val stale = pendingPermRequestEpoch > 0 &&
        (SystemClock.elapsedRealtime() - pendingPermRequestEpoch) > 5000
      if (!stale) {
        pendingPermissionResult = result
        permissionRequestedByInit = fromInit
        return true
      }
      pendingPermissionResult?.error(
        "PERMISSION_REQUEST_STUCK",
        "Previous permission request did not return; retrying.",
        null
      )
      pendingPermissionResult = null
      pendingPermsInFlight = false
      pendingPermRequestEpoch = 0L
    }

    pendingPermissionResult = result
    permissionRequestedByInit = fromInit
    lastRequestedPerms = missing.toTypedArray()
    markAsked(lastRequestedPerms)
    pendingPermsInFlight = true
    pendingPermRequestEpoch = SystemClock.elapsedRealtime()
    ActivityCompat.requestPermissions(host, lastRequestedPerms!!, REQ_CODE)
    return true
  }

  private fun settlePendingPermissionIfAny() {
    val r = pendingPermissionResult ?: return
    if (!pendingPermsInFlight && pendingPermRequestEpoch == 0L) return

    pendingPermsInFlight = false
    pendingPermRequestEpoch = 0L

    if (hasAllRequiredPermissions()) {
      if (permissionRequestedByInit) {
        postMain { initializeAll(r) }
      } else {
        r.success(true)
      }
    } else {
      val missing = missingPermissions()
      val permanentlyDenied = isPermanentlyDeniedNow(missing)
      val code = if (permanentlyDenied) "PERMISSION_PERMANENTLY_DENIED" else "PERMISSION_DENIED"
      val msg = if (permanentlyDenied)
        "Permissions denied with 'Don't ask again'. Open app settings to grant permissions."
      else
        "Bluetooth/Location permissions denied"
      r.error(code, msg, null)
    }
    pendingPermissionResult = null
    permissionRequestedByInit = false
  }

  private fun init(result: MethodChannel.Result) {
    if (initInProgress) {
      result.error("INIT_IN_PROGRESS", "Initialization is already running", null)
      return
    }

    if (!isActivityAttached || activity == null) {
      Log.w(TAG, "init() called before Activity attached; deferring.")
      pendingInitResult?.error("CANCELLED", "Superseded by a new init()", null)
      pendingInitResult = result
      return
    }

    if (!hasAllRequiredPermissions()) {
      if (requestMissingPermissionsIfPossible(result, fromInit = true)) return
    }

    initInProgress = true
    initializeAll(object : MethodChannel.Result {
      override fun success(o: Any?) { initInProgress = false; result.success(o) }
      override fun error(code: String, msg: String?, details: Any?) { initInProgress = false; result.error(code, msg, details) }
      override fun notImplemented() { initInProgress = false; result.notImplemented() }
    })
  }

  // return true if an RFID reader is connected; false otherwise
  private fun initializeAll(result: MethodChannel.Result) {
    thread {
      try {
        var connectedNow = false
        try {
          connectRfidBlockingWithBatchRecovery()
          reader?.let { configureRfid(it) }
          connectedNow = (reader?.isConnected == true)
        } catch (e: NoReaderException) {
          Log.i(TAG, "No RFID reader available; barcode will only work if sled is present.")
          connectedNow = false
        }

        runOnMainAndWait { ensureSCSHandlerOnMain() }

        postMain { result.success(connectedNow) }
      } catch (e: OperationFailureException) {
        Log.e(TAG, "RFID connect failed: ${e.statusDescription}", e)
        val msg = e.vendorMessage ?: e.localizedMessage
        postMain { result.error("CONNECT_FAILED", msg, null) }
      } catch (e: PreferredNotFoundException) {
        postMain { result.error("PREFERRED_NOT_FOUND", e.message, null) }
      } catch (e: Exception) {
        Log.e(TAG, "Init error", e)
        postMain { result.error("INIT_ERROR", e.localizedMessage, null) }
      }
    }
  }
  //endregion

  //region Readers (API3)
  // IMPORTANT: reuse shared Readers; only create a temporary one when we don't have a shared instance.
  private fun listAvailableReaders(): List<Map<String, Any?>> {
    val usingShared = (readers != null)
    val local = if (usingShared) readers!! else Readers(appContext, ENUM_TRANSPORT.BLUETOOTH)

    val available = try { local.GetAvailableRFIDReaderList() } catch (_: Exception) { null } ?: emptyList()

    val mapped = available.map { dev ->
      val r = dev.rfidReader
      val isConnected = try { r?.isConnected == true } catch (_: Exception) { false }
      mapOf(
        "name" to (dev.name ?: r?.hostName ?: "Unknown"),
        "address" to (dev.address ?: ""),
        "connected" to isConnected
      )
    }

    if (!usingShared) {
      try { local.Dispose() } catch (_: Exception) {}
    }
    return mapped
  }
  //endregion

  //========================================================
  //  Readers (API3) – connect + batch-mode recovery
  //========================================================
  @Throws(Exception::class)
  private fun connectRfidBlockingWithBatchRecovery() {
    if (readers == null) {
      val ctx = activity ?: appContext
      readers = Readers(ctx, ENUM_TRANSPORT.BLUETOOTH)
    }

    var list: ArrayList<ReaderDevice>? = null
    repeat(3) {
      list = try { readers?.GetAvailableRFIDReaderList() } catch (_: Exception) { null }
      if (!list.isNullOrEmpty()) return@repeat
      SystemClock.sleep(200)
    }
    if (list.isNullOrEmpty()) throw NoReaderException("No RFID reader found. Pair and power on a Zebra reader.")

    val byAddress = preferredReaderAddress?.let { addrPref ->
      list!!.firstOrNull { (it.address ?: "").normalizeBtAddr() == addrPref }
    }
    val byName = if (byAddress == null && !preferredReaderName.isNullOrBlank()) {
      list!!.firstOrNull { it.name?.equals(preferredReaderName, ignoreCase = true) == true }
    } else null

    if ((preferredReaderAddress != null || !preferredReaderName.isNullOrBlank()) && byAddress == null && byName == null) {
      throw PreferredNotFoundException("Preferred reader not found (name/address mismatch).")
    }

    val candidate = byAddress ?: byName
      ?: list!!.firstOrNull { it.rfidReader?.isConnected == true } ?: list!![0]

    readerDevice = candidate
    val r = candidate.rfidReader ?: throw NoReaderException("No RFIDReader handle")

    try {
      r.connect()
      reader = r
      return
    } catch (e: OperationFailureException) {
      if (!isBatchModeError(e)) throw e
      Log.w(TAG, "connect() -> BATCHMODE_IN_PROGRESS, recovery A...")
      try {
        try { r.Actions.Inventory.stop() } catch (_: Exception) {}
        SystemClock.sleep(120)
        try { r.Actions.getBatchedTags() } catch (_: Exception) {}
        SystemClock.sleep(80)
        try {
          try { r.Actions.purgeTags() } catch (_: NoSuchMethodError) {
            try { r.Actions.javaClass.getMethod("purgeBatchedTags").invoke(r.Actions) } catch (_: Exception) {}
          }
        } catch (_: Exception) {}
      } catch (_: Exception) {}
      try { r.disconnect() } catch (_: Exception) {}
      SystemClock.sleep(200)

      try {
        r.connect()
        reader = r
        handleBatchModeAfterConnect(r)
        return
      } catch (_: OperationFailureException) {
        Log.w(TAG, "connect retry failed, reinit transport & re-enum (phase B)")
      }

      try { readers?.Dispose() } catch (_: Exception) {}
      readers = null
      SystemClock.sleep(250)
      val ctx = activity ?: appContext
      readers = Readers(ctx, ENUM_TRANSPORT.BLUETOOTH)
      val list2 = readers?.GetAvailableRFIDReaderList() ?: throw NoReaderException("No RFID reader found after recovery.")
      val again = preferredReaderAddress?.let { addr ->
        list2.firstOrNull { (it.address ?: "").normalizeBtAddr() == addr }
      } ?: list2[0]

      readerDevice = again
      val r2 = again.rfidReader ?: throw NoReaderException("No RFIDReader handle after recovery")
      r2.connect()
      reader = r2
      handleBatchModeAfterConnect(r2)
    }
  }

  private fun isBatchModeError(e: OperationFailureException): Boolean {
    val s = (e.statusDescription ?: "") + " " + (e.vendorMessage ?: "")
    val up = s.uppercase()
    return up.contains("BATCHMODE") || up.contains("BATCH_MODE") || up.contains("RFID_BATCHMODE_IN_PROGRESS")
  }

  private fun handleBatchModeAfterConnect(r: RFIDReader) {
    try {
      try { r.Actions.Inventory.stop() } catch (_: Exception) {}
      SystemClock.sleep(120)
      try { r.Actions.getBatchedTags() } catch (_: Exception) {}
      SystemClock.sleep(80)
      try {
        try { r.Actions.purgeTags() } catch (_: NoSuchMethodError) {
          try { r.Actions.javaClass.getMethod("purgeBatchedTags").invoke(r.Actions) } catch (_: Exception) {}
        }
      } catch (_: Exception) {}
      SystemClock.sleep(80)
    } catch (ex: Exception) {
      Log.w(TAG, "Batch-mode post-connect cleanup failed: ${ex.message}")
    }
  }

  //========================================================
  //  RFID config & events
  //========================================================
  @Throws(InvalidUsageException::class, OperationFailureException::class)
  private fun configureRfid(r: RFIDReader) {
    val ti = TriggerInfo().apply {
      StartTrigger.triggerType = START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE
      StopTrigger.triggerType  = STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE
    }
    r.Config.setStartTrigger(ti.StartTrigger)
    r.Config.setStopTrigger(ti.StopTrigger)
    safeSetTriggerMode(r, ENUM_TRIGGER_MODE.RFID_MODE)

    r.Events.apply {
      setTagReadEvent(true)
      setAttachTagDataWithReadEvent(false)
      setReaderDisconnectEvent(true)
      setHandheldEvent(true)
      setBatchModeEvent(true)
    }

    val listener = object : RfidEventsListener {
      override fun eventReadNotify(e: RfidReadEvents?) {
        try {
          val tags = r.Actions.getReadTags(100)
          tags?.forEach { t ->
            t.tagID?.let { epc ->
              if (epc.isNotEmpty()) postMain { channel.invokeMethod("onScan", epc) }
            }
          }
        } catch (ex: Exception) {
          Log.e(TAG, "read tags error", ex)
        }
      }

      override fun eventStatusNotify(e: RfidStatusEvents?) {
        val d = e?.StatusEventData ?: return
        when (d.statusEventType) {
          STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT -> {
            val ev = d.HandheldTriggerEventData.handheldEvent
            Log.d(TAG, "Trigger: $ev (active=$isActive, running=$isInventoryRunning, mode=$currentMode, scs=${activeScannerId>0})")
            when (ev) {
              HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED -> {
                isTriggerHeld = true
                if (!isActive) return
                if (!isInventoryRunning && shouldStartRfidInventoryNow()) {
                  try { r.Actions.Inventory.perform(); isInventoryRunning = true }
                  catch (ofe: OperationFailureException) { Log.w(TAG, "perform() failed: ${ofe.statusDescription}") }
                  catch (x: Exception) { Log.e(TAG, "perform() error", x) }
                }
              }
              HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED -> {
                isTriggerHeld = false
                if (isInventoryRunning) stopInventorySafe(r)
              }
              else -> Unit
            }
          }
          STATUS_EVENT_TYPE.BATCH_MODE_EVENT -> Log.i(TAG, "BATCH_MODE_EVENT")
          STATUS_EVENT_TYPE.DISCONNECTION_EVENT -> {
            Log.w(TAG, "RFID disconnected")
            postMain { channel.invokeMethod("onDisconnected", null) }
            try { r.disconnect() } catch (_: Exception) {}
            reader = null; readerDevice = null
            isInventoryRunning = false; isActive = false; isTriggerHeld = false
            runOnMainAndWait { scsTerminateSessionOnMain() }
          }
          else -> Unit
        }
      }
    }
    rfidListener = listener
    r.Events.addEventsListener(listener)
  }

  private fun shouldStartRfidInventoryNow(): Boolean {
    return when (currentMode) {
      "RFID" -> true
      "BARCODE" -> false
      "BARCODE_RFID" -> true
      else -> false
    }
  }

  private fun stopInventorySafe(r: RFIDReader?) {
    try { if (r != null && isInventoryRunning) r.Actions.Inventory.stop() }
    catch (_: Exception) {}
    finally { isInventoryRunning = false }
  }

  //========================================================
  //  Scanner Control SDK (SCS)
  //========================================================
  private fun ensureSCSHandlerOnMain() {
    if (sdkHandler != null) return
    val sh = SDKHandler(appContext, false)
    sdkHandler = sh

    sh.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_BT_NORMAL)
    sh.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_BT_LE)
    sh.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_USB_CDC)

    var mask = 0
    mask = mask or DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_APPEARANCE.value
    mask = mask or DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_DISAPPEARANCE.value
    mask = mask or DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_ESTABLISHMENT.value
    mask = mask or DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_TERMINATION.value
    mask = mask or DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_BARCODE.value
    sh.dcssdkSubsribeForEvents(mask)
    sh.dcssdkSetDelegate(this)
    sh.dcssdkEnableAvailableScannersDetection(true)

    isSCSReady = true
  }

  private fun scsEnsureSessionOnMain() {
    val sh = sdkHandler ?: return
    if (activeScannerId > 0) return

    val list = ArrayList<DCSScannerInfo>()
    sh.dcssdkGetAvailableScannersList(list)
    if (list.isEmpty()) {
      Log.i(TAG, "SCS: no available scanners (no sled?).")
      return
    }

    val api3Addr = readerDevice?.address?.normalizeBtAddr()
    val byAddr = if (!api3Addr.isNullOrBlank()) {
      list.firstOrNull { getBtAddressCompat(it)?.normalizeBtAddr() == api3Addr }
    } else null

    val api3Name = readerDevice?.name
    val byName = if (byAddr == null && !api3Name.isNullOrBlank()) {
      list.firstOrNull { it.scannerName?.equals(api3Name, ignoreCase = true) == true }
    } else null

    val target = byAddr ?: byName ?: list[0]

    if (!target.isActive) {
      val res = sh.dcssdkEstablishCommunicationSession(target.scannerID)
      Log.d(TAG, "SCS establish result=$res id=${target.scannerID}")
    } else {
      activeScannerId = target.scannerID
      Log.d(TAG, "SCS already active: ${target.scannerName} (${target.scannerID})")
    }
  }

  private fun scsTerminateSessionOnMain() {
    try {
      val sh = sdkHandler ?: return
      if (activeScannerId > 0) {
        sh.dcssdkTerminateCommunicationSession(activeScannerId)
      }
    } catch (_: Exception) {
    } finally {
      activeScannerId = -1
    }
  }

  // -- IDcsSdkApiDelegate --
  override fun dcssdkEventScannerAppeared(availableScanner: DCSScannerInfo?) {
    Log.i(TAG, "SCS: scanner appeared: ${availableScanner?.scannerName}")
  }
  override fun dcssdkEventScannerDisappeared(scannerID: Int) {
    Log.i(TAG, "SCS: scanner disappeared id=$scannerID")
    if (scannerID == activeScannerId) activeScannerId = -1
  }
  override fun dcssdkEventCommunicationSessionEstablished(activeScanner: DCSScannerInfo?) {
    activeScannerId = activeScanner?.scannerID ?: -1
    Log.i(TAG, "SCS: session established with ${activeScanner?.scannerName} id=$activeScannerId")
  }
  override fun dcssdkEventCommunicationSessionTerminated(scannerID: Int) {
    Log.w(TAG, "SCS: session terminated id=$scannerID")
    if (scannerID == activeScannerId) activeScannerId = -1
  }
  override fun dcssdkEventBarcode(barcodeData: ByteArray, barcodeType: Int, fromScannerID: Int) {
    if (!isActive) return
    try {
      val data = String(barcodeData, Charsets.US_ASCII)
      postMain { channel.invokeMethod("onBarcodeScan", data) }
    } catch (e: Exception) {
      Log.e(TAG, "SCS: barcode parse error", e)
    }
  }
  override fun dcssdkEventBinaryData(binaryData: ByteArray, fromScannerID: Int) {}
  override fun dcssdkEventFirmwareUpdate(p0: FirmwareUpdateEvent?) {}
  override fun dcssdkEventImage(imageData: ByteArray, fromScannerID: Int) {}
  override fun dcssdkEventVideo(videoFrame: ByteArray, fromScannerID: Int) {}
  override fun dcssdkEventAuxScannerAppeared(newTopology: DCSScannerInfo?, auxScanner: DCSScannerInfo?) {}

  //========================================================
  //  Modes & routing
  //========================================================
  private var currentMode: String = "OFF"

  private fun setModeInternal(mode: String) {
    val m = when (mode) { "RFID", "BARCODE", "BARCODE_RFID", "OFF" -> mode; else -> "OFF" }
    currentMode = m

    if (!isActive) {
      stopInventorySafe(reader)
      runOnMainAndWait { scsTerminateSessionOnMain() }
      return
    }
    applyRoutingForCurrentMode()
  }

  private fun applyRoutingForCurrentMode() {
    if (!isActive) return
    when (currentMode) {
      "OFF" -> {
        stopInventorySafe(reader)
        runOnMainAndWait { scsTerminateSessionOnMain() }
      }
      "RFID" -> {
        runOnMainAndWait { scsTerminateSessionOnMain() }
      }
      "BARCODE" -> {
        stopInventorySafe(reader)
        runOnMainAndWait { ensureSCSHandlerOnMain(); scsEnsureSessionOnMain() }
      }
      "BARCODE_RFID" -> {
        runOnMainAndWait { ensureSCSHandlerOnMain(); scsEnsureSessionOnMain() }
      }
    }
  }

  //========================================================
  //  Cleanup / Utils
  //========================================================
  private fun cleanupAll() {
    try {
      runOnMainAndWait { scsTerminateSessionOnMain() }
      sdkHandler?.let { try { it.dcssdkClose() } catch (_: Exception) {} }
    } catch (_: Exception) { }
    sdkHandler = null
    isSCSReady = false

    try {
      reader?.let { r ->
        try { rfidListener?.let { r.Events.removeEventsListener(it) } } catch (_: Exception) {}
        rfidListener = null
        stopInventorySafe(r)
        try { if (r.isConnected) r.disconnect() } catch (_: Exception) {}
      }
      readers?.Dispose()
    } catch (_: Exception) { }
    reader = null; readerDevice = null; readers = null

    isActive = false
    isTriggerHeld = false
    isInventoryRunning = false
  }

  private fun postMain(block: () -> Unit) {
    Handler(Looper.getMainLooper()).post { block() }
  }

  private fun <T> runOnMainAndWait(block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val latch = CountDownLatch(1)
    val resultRef = AtomicReference<T>()
    val errorRef = AtomicReference<Throwable?>(null)
    postMain {
      try { resultRef.set(block()) } catch (t: Throwable) { errorRef.set(t) } finally { latch.countDown() }
    }
    latch.await()
    @Suppress("UNCHECKED_CAST")
    if (errorRef.get() != null) throw errorRef.get()!!
    return resultRef.get() as T
  }

  private fun safeSetTriggerMode(r: RFIDReader, mode: ENUM_TRIGGER_MODE): Boolean {
    return try { r.Config.setTriggerMode(mode, true); true }
    catch (_: Exception) { Log.w(TAG, "setTriggerMode failed for $mode"); false }
  }

  private fun String.normalizeBtAddr(): String =
    this.replace(":", "").replace("-", "").trim().lowercase()
}
// ====== /class ZebraRfidPlugin ======


// -----------------------
// Top-level helpers
// -----------------------
private class NoReaderException(msg: String): Exception(msg)
private class PreferredNotFoundException(msg: String): Exception(msg)

private fun getBtAddressCompat(info: com.zebra.scannercontrol.DCSScannerInfo): String? {
  return try {
    val f1 = info.javaClass.getDeclaredField("bluetoothAddress"); f1.isAccessible = true; (f1.get(info) as? String)
  } catch (_: Exception) {
    try {
      val f2 = info.javaClass.getDeclaredField("BDAddress"); f2.isAccessible = true; (f2.get(info) as? String)
    } catch (_: Exception) {
      try {
        val f3 = info.javaClass.getDeclaredField("mBluetoothAddress"); f3.isAccessible = true; (f3.get(info) as? String)
      } catch (_: Exception) { null }
    }
  }
}
