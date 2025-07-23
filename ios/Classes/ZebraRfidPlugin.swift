import Flutter
import UIKit
import RFIDFramework
import BarcodeFramework

public class ZebraRfidPlugin: NSObject, FlutterPlugin {
    var channel: FlutterMethodChannel?
    var rfidReader: srfidReaderInfo?
    var api: srfidSdkApi?
    var barcodeMode: Bool = false

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "zebra_rfid_plugin", binaryMessenger: registrar.messenger())
        let instance = ZebraRfidPlugin()
        instance.channel = channel
        registrar.addMethodCallDelegate(instance, channel: channel)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "init":
            initReader(result: result)
        case "startRfid":
            startInventory(result: result)
        case "stopRfid":
            stopInventory(result: result)
        case "setPower":
            if let args = call.arguments as? Int {
                setPower(level: args, result: result)
            } else {
                result(FlutterError(code: "INVALID_ARGUMENT", message: "Power level is required", details: nil))
            }
        case "switchTrigger":
            if let mode = call.arguments as? String {
                switchTriggerMode(mode: mode, result: result)
            } else {
                result(FlutterError(code: "INVALID_ARGUMENT", message: "Mode must be a string", details: nil))
            }
        case "getPlatformVersion":
            result("iOS " + UIDevice.current.systemVersion)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    private func initReader(result: @escaping FlutterResult) {
        api = srfidSdkApi.sharedInstance()
        api?.srfidEnableAvailableReadersDetection(true)
        api?.srfidSetDelegate(self)

        // Auto connect to first reader
        if let connectedReaders = api?.srfidGetAvailableReadersList(), let first = connectedReaders.first as? srfidReaderInfo {
            rfidReader = first
            api?.srfidEstablishCommunicationSession(Int32(first.getReaderID()))
            result(true)
        } else {
            result(FlutterError(code: "NO_READER", message: "No RFID reader found", details: nil))
        }
    }

    private func startInventory(result: @escaping FlutterResult) {
        guard let reader = rfidReader else {
            result(FlutterError(code: "NO_READER", message: "Reader not initialized", details: nil))
            return
        }

        api?.srfidStartInventory(Int32(reader.getReaderID()), aMemoryBank: INVENTORY_MEMORY_BANK_EPC, aReportConfig: nil)
        result(true)
    }

    private func stopInventory(result: @escaping FlutterResult) {
        guard let reader = rfidReader else {
            result(FlutterError(code: "NO_READER", message: "Reader not initialized", details: nil))
            return
        }

        api?.srfidStopInventory(Int32(reader.getReaderID()))
        result(true)
    }

    private func setPower(level: Int, result: @escaping FlutterResult) {
        guard let reader = rfidReader else {
            result(FlutterError(code: "NO_READER", message: "Reader not initialized", details: nil))
            return
        }

        guard level >= 0 && level <= 300 else {
            result(FlutterError(code: "INVALID_POWER", message: "Power level must be between 0 and 300", details: nil))
            return
        }

        let xmlCommand = """
        <RFID_COMMAND>
            <SET_ANTENNA_CONFIGURATION>
                <ANTENNAID>0</ANTENNAID>
                <RFMODETABLEINDEX>0</RFMODETABLEINDEX>
                <TARI>0</TARI>
                <POWER>\(level)</POWER>
            </SET_ANTENNA_CONFIGURATION>
        </RFID_COMMAND>
        """

        var response: NSString? = nil
        var errorResponse: NSString? = nil

        let status = api?.srfidExecuteCommand(
            Int32(reader.getReaderID()),
            aXmlCommand: xmlCommand,
            aCommandResponse: &response,
            aCommandErrorResponse: &errorResponse
        )

        if status == RFID_RESULT_SUCCESS {
            result(true)
        } else {
            result(FlutterError(
                code: "SET_POWER_FAILED",
                message: "Failed to set power",
                details: errorResponse as String? ?? "Unknown error"
            ))
        }
    }

    private func switchTriggerMode(mode: String, result: @escaping FlutterResult) {
        guard let reader = rfidReader else {
            result(FlutterError(code: "NO_READER", message: "Reader not initialized", details: nil))
            return
        }

        if mode.lowercased() == "barcode" {
            barcodeMode = true
            // DataWedge or ScannerControl SDK can be triggered here if supported
            result("Switched to Barcode mode")
        } else if mode.lowercased() == "rfid" {
            barcodeMode = false
            result("Switched to RFID mode")
        } else {
            result(FlutterError(code: "INVALID_MODE", message: "Mode must be 'rfid' or 'barcode'", details: nil))
        }
    }
}

extension ZebraRfidPlugin: srfidSdkApiDelegate {
    public func srfidEventReadNotify(_ readerID: Int32, aTagData: [Any]!) {
        for tag in aTagData {
            if let tag = tag as? srfidTagData {
                let tagID = tag.getTagID()
                channel?.invokeMethod("onScan", arguments: tagID)
            }
        }
    }

    public func srfidEventBarcodeData(_ readerID: Int32, aBarcodeData: String!, aBarcodeType: Int32) {
        if barcodeMode {
            channel?.invokeMethod("onScan", arguments: aBarcodeData)
        }
    }

    public func srfidEventCommunicationSessionEstablished(_ readerID: Int32) {}
    public func srfidEventCommunicationSessionTerminated(_ readerID: Int32) {}
    public func srfidEventReaderAppeared(_ availableReader: srfidReaderInfo!) {}
    public func srfidEventReaderDisappeared(_ readerID: Int32) {}
}

