// Captures the Minecraft client window via ScreenCaptureKit, even when other windows cover it.
//   wincap shot   <out.png>
//   wincap record <out.mov> <seconds> [width height fps]
//   wincap record <out.mov> until <stopfile> [width height fps]   (stops when <stopfile> exists)
import AVFoundation
import CoreImage
import ScreenCaptureKit

func die(_ msg: String) -> Never {
    FileHandle.standardError.write((msg + "\n").data(using: .utf8)!)
    exit(1)
}

func findWindow() async throws -> SCWindow {
    let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false)
    let candidates = content.windows.filter {
        ($0.title ?? "").hasPrefix("Minecraft") && $0.frame.width > 200
    }
    guard let window = candidates.max(by: { $0.frame.width < $1.frame.width }) else {
        die("no Minecraft window found")
    }
    return window
}

final class Recorder: NSObject, SCStreamOutput {
    let writer: AVAssetWriter
    let input: AVAssetWriterInput
    var started = false
    var frames = 0

    init(url: URL, width: Int, height: Int) throws {
        try? FileManager.default.removeItem(at: url)
        writer = try AVAssetWriter(outputURL: url, fileType: .mov)
        input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.hevc,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 40_000_000],
        ])
        input.expectsMediaDataInRealTime = true
        writer.add(input)
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer buffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, buffer.isValid,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(buffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let raw = attachments.first?[.status] as? Int,
              SCFrameStatus(rawValue: raw) == .complete else { return }
        if !started {
            // wall clock of the first frame, so shot marks from the server can be mapped onto the video
            let epochMs = Int64(Date().timeIntervalSince1970 * 1000)
            try? "\(epochMs)\n".write(to: writer.outputURL.appendingPathExtension("start"), atomically: true, encoding: .utf8)
            writer.startWriting()
            writer.startSession(atSourceTime: buffer.presentationTimeStamp)
            started = true
        }
        if input.isReadyForMoreMediaData {
            input.append(buffer)
            frames += 1
        }
    }
}

// ScreenCaptureKit asserts unless CoreGraphics has a connection to the window server.
_ = CGMainDisplayID()

let args = CommandLine.arguments
guard args.count >= 3 else { die("usage: wincap shot <out.png> | record <out.mov> <seconds|until stopfile> [w h fps]") }

let done = DispatchSemaphore(value: 0)
Task {
    do {
        let window = try await findWindow()
        let filter = SCContentFilter(desktopIndependentWindow: window)
        let config = SCStreamConfiguration()
        config.showsCursor = false
        config.ignoreShadowsSingleWindow = true
        // drop the title bar: the game area is 16:9 (launched with a 16:9 --resolution)
        let content = CGSize(width: window.frame.width, height: window.frame.width * 9 / 16)
        config.sourceRect = CGRect(x: 0, y: window.frame.height - content.height, width: content.width, height: content.height)

        if args[1] == "shot" {
            config.width = Int(content.width * 2)
            config.height = Int(content.height * 2)
            let image = try await SCScreenshotManager.captureImage(contentFilter: filter, configuration: config)
            let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: args[2]) as CFURL, "public.png" as CFString, 1, nil)!
            CGImageDestinationAddImage(dest, image, nil)
            CGImageDestinationFinalize(dest)
            print("shot \(image.width)x\(image.height) -> \(args[2])")
        } else if args[1] == "record" {
            var rest = Array(args.dropFirst(3))
            var stopFile: String? = nil
            var seconds = 0.0
            if rest.first == "until" { stopFile = rest[1]; rest.removeFirst(2) } else { seconds = Double(rest.removeFirst())! }
            let width = rest.count > 0 ? Int(rest[0])! : 1920
            let height = rest.count > 1 ? Int(rest[1])! : 1080
            let fps = rest.count > 2 ? Int32(rest[2])! : 60
            config.width = width
            config.height = height
            config.minimumFrameInterval = CMTime(value: 1, timescale: fps)
            config.queueDepth = 8
            let recorder = try Recorder(url: URL(fileURLWithPath: args[2]), width: width, height: height)
            let stream = SCStream(filter: filter, configuration: config, delegate: nil)
            try stream.addStreamOutput(recorder, type: .screen, sampleHandlerQueue: DispatchQueue(label: "wincap"))
            try await stream.startCapture()
            print("recording \(window.title ?? "") -> \(args[2])")
            if let stopFile {
                while !FileManager.default.fileExists(atPath: stopFile) { try await Task.sleep(nanoseconds: 50_000_000) }
            } else {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            }
            try await stream.stopCapture()
            recorder.input.markAsFinished()
            await recorder.writer.finishWriting()
            print("frames \(recorder.frames)")
        } else {
            die("unknown mode \(args[1])")
        }
    } catch {
        die("error: \(error)")
    }
    done.signal()
}
done.wait()
