#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <memory>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <algorithm>

#include "Core/Shared/Emulator.h"
#include "Core/Shared/Interfaces/IConsole.h"
#include "Core/Shared/Interfaces/IAudioDevice.h"
#include "Core/Shared/Interfaces/IRenderingDevice.h"
#include "Core/Shared/Interfaces/IKeyManager.h"
#include "Core/Shared/KeyManager.h"
#include "Core/Shared/SystemActionManager.h"
#include "Core/Shared/RenderedFrame.h"
#include "Core/Shared/EmuSettings.h"
#include "Core/Shared/SettingTypes.h"
#include "Core/Shared/EmulatorLock.h"
#include "Core/Shared/MessageManager.h"
#include "Core/Shared/Video/VideoRenderer.h"
#include "Core/Shared/Audio/SoundMixer.h"
#include "Utilities/VirtualFile.h"
#include "Utilities/ArchiveReader.h"
#include "Utilities/FolderUtilities.h"
#include "Core/Shared/SaveStateManager.h"
#include "Core/Shared/CheatManager.h"
#include "Core/Shared/RewindManager.h"
#include <aaudio/AAudio.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "MesenJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MesenJNI", __VA_ARGS__)

using namespace std;

class AndroidAudioDevice : public IAudioDevice {
private:
    AAudioStream* _stream = nullptr;
    uint32_t _sampleRate = 48000;
    std::recursive_mutex _audioMutex;
public:
    AndroidAudioDevice(Emulator* emu) {
        emu->GetSoundMixer()->RegisterAudioDevice(this);
        InitStream(48000);
    }
    virtual ~AndroidAudioDevice() override {
        CloseStream();
    }

    void InitStream(uint32_t sampleRate) {
        std::lock_guard<std::recursive_mutex> lock(_audioMutex);
        CloseStream();
        _sampleRate = sampleRate;
        AAudioStreamBuilder* builder = nullptr;
        if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
            LOGE("AAudio_createStreamBuilder failed");
            return;
        }

        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setChannelCount(builder, 2);
        AAudioStreamBuilder_setSampleRate(builder, sampleRate);
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);

        aaudio_result_t res = AAudioStreamBuilder_openStream(builder, &_stream);
        if (res != AAUDIO_OK) {
            LOGE("AAudio openStream LOW_LATENCY failed: %d, trying NONE", res);
            AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);
            res = AAudioStreamBuilder_openStream(builder, &_stream);
        }

        if (res == AAUDIO_OK && _stream) {
            int32_t burst = AAudioStream_getFramesPerBurst(_stream);
            int32_t capacity = AAudioStream_getBufferCapacityInFrames(_stream);
            int32_t targetBuffer = max((int32_t)(burst * 8), (int32_t)2048);
            if (capacity > 0) {
                targetBuffer = min(targetBuffer, capacity);
            }
            AAudioStream_setBufferSizeInFrames(_stream, targetBuffer);
            res = AAudioStream_requestStart(_stream);
            LOGI("AAudioStream started: res=%d, sampleRate=%u, burst=%d, bufferSize=%d, capacity=%d", 
                 res, sampleRate, burst, targetBuffer, capacity);
        } else {
            LOGE("AAudio openStream failed completely: %d", res);
            _stream = nullptr;
        }

        AAudioStreamBuilder_delete(builder);
    }

    void CloseStream() {
        if (_stream) {
            AAudioStream_requestStop(_stream);
            AAudioStream_close(_stream);
            _stream = nullptr;
        }
    }

    void PlayBuffer(int16_t* soundBuffer, uint32_t bufferSize, uint32_t sampleRate, bool isStereo) override {
        std::lock_guard<std::recursive_mutex> lock(_audioMutex);
        if (!_stream || _sampleRate != sampleRate) {
            InitStream(sampleRate);
        }
        if (!_stream) return;

        aaudio_stream_state_t state = AAudioStream_getState(_stream);
        if (state == AAUDIO_STREAM_STATE_STOPPED || state == AAUDIO_STREAM_STATE_PAUSED ||
            state == AAUDIO_STREAM_STATE_FLUSHED || state == AAUDIO_STREAM_STATE_STOPPING) {
            AAudioStream_requestStart(_stream);
        } else if (state == AAUDIO_STREAM_STATE_DISCONNECTED || state == AAUDIO_STREAM_STATE_CLOSING || state == AAUDIO_STREAM_STATE_CLOSED) {
            InitStream(sampleRate);
            if (!_stream) return;
        }

        if (soundBuffer && bufferSize > 0) {
            // Non-blocking write: write as many samples as fit in the buffer immediately.
            // Mesen has its own FrameLimiter master timer. A blocking audio write stalls the
            // emulation thread for 12ms per frame, destroying GBA/SFC speed and making Turbo impossible.
            aaudio_result_t written = AAudioStream_write(_stream, soundBuffer, (int32_t)bufferSize, 0LL);
            if (written < 0) {
                if (written == AAUDIO_ERROR_DISCONNECTED || written == AAUDIO_ERROR_INVALID_STATE) {
                    InitStream(sampleRate);
                }
            }
        }
    }

    void Stop() override {
        std::lock_guard<std::recursive_mutex> lock(_audioMutex);
        if (_stream) AAudioStream_requestPause(_stream);
    }

    void Pause() override {
        std::lock_guard<std::recursive_mutex> lock(_audioMutex);
        if (_stream) AAudioStream_requestPause(_stream);
    }

    void ProcessEndOfFrame() override {}
    string GetAvailableDevices() override { return ""; }
    void SetAudioDevice(string deviceName) override {}
    AudioStatistics GetStatistics() override { return AudioStatistics(); }
};

class AndroidRenderingDevice : public IRenderingDevice {
public:
    std::mutex bufferMutex;
    std::vector<uint32_t> frameCopy;
    uint32_t latestWidth = 0;
    uint32_t latestHeight = 0;

    AndroidRenderingDevice(Emulator* emu) {
        emu->GetVideoRenderer()->RegisterRenderingDevice(this);
    }
    virtual ~AndroidRenderingDevice() override {}

    void UpdateFrame(RenderedFrame& frame) override {
        std::lock_guard<std::mutex> lock(bufferMutex);
        latestWidth = frame.Width;
        latestHeight = frame.Height;
        size_t pixelCount = frame.Width * frame.Height;
        if (frameCopy.size() < pixelCount) {
            frameCopy.resize(pixelCount);
        }
        if (frame.FrameBuffer) {
            const uint32_t* src = static_cast<const uint32_t*>(frame.FrameBuffer);
            uint32_t* dst = frameCopy.data();
            for (size_t i = 0; i < pixelCount; ++i) {
                uint32_t c = src[i];
                dst[i] = 0xFF000000 | (c & 0x0000FF00) | ((c & 0x00FF0000) >> 16) | ((c & 0x000000FF) << 16);
            }
        }
    }
    
    void ClearFrame() override {}
    void Render(RenderSurfaceInfo& emuHud, RenderSurfaceInfo& scriptHud) override {}
    void Reset() override {}
    void SetExclusiveFullscreenMode(bool fullscreen, void* windowHandle) override {}
};

class AndroidKeyManager : public IKeyManager {
private:
    std::atomic<bool> _keyState[256] = {};
    std::atomic<bool> _disabled{false};
public:
    virtual ~AndroidKeyManager() override {}
    void RefreshState() override {}
    void UpdateDevices() override {}
    bool IsMouseButtonPressed(MouseButton button) override { return false; }
    bool IsKeyPressed(uint16_t keyCode) override {
        if (_disabled.load(std::memory_order_relaxed) || keyCode >= 256) return false;
        return _keyState[keyCode].load(std::memory_order_acquire);
    }
    vector<uint16_t> GetPressedKeys() override {
        vector<uint16_t> pressed;
        for (int i = 0; i < 256; i++) {
            if (_keyState[i].load(std::memory_order_relaxed)) pressed.push_back(i);
        }
        return pressed;
    }
    string GetKeyName(uint16_t keyCode) override { return ""; }
    uint16_t GetKeyCode(string keyName) override { return 0; }

    bool SetKeyState(uint16_t scanCode, bool state) override {
        if (scanCode < 256) {
            _keyState[scanCode].store(state, std::memory_order_release);
            return true;
        }
        return false;
    }
    void ResetKeyState() override {
        for (int i = 0; i < 256; i++) {
            _keyState[i].store(false, std::memory_order_relaxed);
        }
    }
    void SetDisabled(bool disabled) override {
        _disabled.store(disabled, std::memory_order_relaxed);
    }
};

static unique_ptr<Emulator> g_emu;
static unique_ptr<AndroidRenderingDevice> g_renderer;
static unique_ptr<AndroidAudioDevice> g_audio;
static unique_ptr<AndroidKeyManager> g_keyManager;

static const uint32_t s_defaultNesPalette[64] = {
    0xFF666666, 0xFF002A88, 0xFF1412A7, 0xFF3B00A4, 0xFF5C007E, 0xFF6E0040, 0xFF6C0600, 0xFF561D00,
    0xFF333500, 0xFF0B4800, 0xFF005200, 0xFF004F08, 0xFF00404D, 0xFF000000, 0xFF000000, 0xFF000000,
    0xFFADADAD, 0xFF155FD9, 0xFF4240FF, 0xFF7527FE, 0xFFA01ACC, 0xFFB71E7B, 0xFFB53120, 0xFF994E00,
    0xFF6B6D00, 0xFF388700, 0xFF0C9300, 0xFF008F32, 0xFF007C8D, 0xFF000000, 0xFF000000, 0xFF000000,
    0xFFFFFEFF, 0xFF64B0FF, 0xFF9290FF, 0xFFC676FF, 0xFFF36AFF, 0xFFFE6ECC, 0xFFFE8170, 0xFFEA9E22,
    0xFFBCBE00, 0xFF88D800, 0xFF5CE430, 0xFF45E082, 0xFF48CDDE, 0xFF4F4F4F, 0xFF000000, 0xFF000000,
    0xFFFFFEFF, 0xFFC0DFFF, 0xFFD3D2FF, 0xFFE8C8FF, 0xFFFBC2FF, 0xFFFEC4EA, 0xFFFECCC5, 0xFFF7D8A5,
    0xFFE4E594, 0xFFCFEF96, 0xFFBDF4AB, 0xFFB3F3CC, 0xFFB5EBF2, 0xFFB8B8B8, 0xFF000000, 0xFF000000
};

static const uint32_t s_defaultPcePalette[512] = {
    0xFF000000, 0xFF000016, 0xFF040235, 0xFF01004B, 0xFF07046A, 0xFF050180, 0xFF0B069F, 0xFF0903B5,
    0xFF1A0003, 0xFF200521, 0xFF1E0237, 0xFF240756, 0xFF22046C, 0xFF28098B, 0xFF2506A1, 0xFF2B0BC0,
    0xFF3D080D, 0xFF3B0524, 0xFF400A42, 0xFF3E0759, 0xFF440C77, 0xFF42098D, 0xFF480EAC, 0xFF450BC2,
    0xFF570910, 0xFF550526, 0xFF5B0A45, 0xFF59075B, 0xFF5E0C7A, 0xFF5C0990, 0xFF620EAF, 0xFF600BC5,
    0xFF720913, 0xFF770E31, 0xFF750B48, 0xFF7B1066, 0xFF790C7C, 0xFF7F119B, 0xFF7C0EB1, 0xFF8213D0,
    0xFF94111E, 0xFF920E34, 0xFF981352, 0xFF951069, 0xFF9B1587, 0xFF99129E, 0xFF9F17BC, 0xFF9D13D2,
    0xFFAE1120, 0xFFAC0E36, 0xFFB21355, 0xFFB0106B, 0xFFB6158A, 0xFFB312A0, 0xFFB917BF, 0xFFB714D5,
    0xFFC91123, 0xFFCF1641, 0xFFCC1358, 0xFFD21876, 0xFFD0158C, 0xFFD61AAB, 0xFFD417C1, 0xFFDA1CE0,
    0xFF092408, 0xFF07211E, 0xFF0D263D, 0xFF0A2353, 0xFF102872, 0xFF0E2588, 0xFF142AA7, 0xFF1127BD,
    0xFF23240B, 0xFF212121, 0xFF27263F, 0xFF252356, 0xFF2A2874, 0xFF28258B, 0xFF2E2AA9, 0xFF2C27BF,
    0xFF3E240D, 0xFF43292C, 0xFF412642, 0xFF472B61, 0xFF452877, 0xFF4B2D95, 0xFF482AAC, 0xFF4E2FCA,
    0xFF602C18, 0xFF5E292E, 0xFF642E4D, 0xFF612B63, 0xFF673082, 0xFF652D98, 0xFF6B32B7, 0xFF692FCD,
    0xFF7A2D1B, 0xFF782931, 0xFF7E2E50, 0xFF7C2B66, 0xFF823084, 0xFF7F2D9B, 0xFF8532B9, 0xFF832FD0,
    0xFF952D1D, 0xFF9B323C, 0xFF982F52, 0xFF9E3471, 0xFF9C3087, 0xFFA236A6, 0xFFA032BC, 0xFFA637DA,
    0xFFB73528, 0xFFB5323E, 0xFFBB375D, 0xFFB93473, 0xFFBF3992, 0xFFBC36A8, 0xFFC23BC7, 0xFFC038DD,
    0xFFD2352B, 0xFFCF3241, 0xFFD53760, 0xFFD33476, 0xFFD93994, 0xFFD736AB, 0xFFDC3BC9, 0xFFDA38E0,
    0xFF0A4008, 0xFF0F4526, 0xFF0D423D, 0xFF13475B, 0xFF114471, 0xFF174990, 0xFF1445A6, 0xFF1A4AC5,
    0xFF2C4813, 0xFF2A4529, 0xFF304A47, 0xFF2D475E, 0xFF334C7C, 0xFF314993, 0xFF2F46A9, 0xFF354BC7,
    0xFF464815, 0xFF44452B, 0xFF4A4A4A, 0xFF484760, 0xFF4E4C7F, 0xFF4B4995, 0xFF514EB4, 0xFF4F4BCA,
    0xFF614818, 0xFF674D36, 0xFF644A4D, 0xFF6A4F6B, 0xFF684C81, 0xFF6E51A0, 0xFF6C4EB6, 0xFF7253D5,
    0xFF835123, 0xFF814D39, 0xFF875257, 0xFF854F6E, 0xFF8B548C, 0xFF8851A3, 0xFF864EB9, 0xFF8C53D7,
    0xFF9E5125, 0xFF9B4D3C, 0xFFA1535A, 0xFF9F4F70, 0xFFA5548F, 0xFFA351A5, 0xFFA856C4, 0xFFA653DA,
    0xFFB85128, 0xFFBE5646, 0xFFBC535D, 0xFFC1587B, 0xFFBF5592, 0xFFC55AB0, 0xFFC356C6, 0xFFC95CE5,
    0xFFDA5933, 0xFFD85649, 0xFFDE5B68, 0xFFDC587E, 0xFFD95594, 0xFFDF5AB3, 0xFFDD57C9, 0xFFE35CE8,
    0xFF126410, 0xFF106126, 0xFF166645, 0xFF14625B, 0xFF1A6779, 0xFF176490, 0xFF1D69AE, 0xFF1B66C5,
    0xFF2D6412, 0xFF336931, 0xFF306647, 0xFF366B66, 0xFF34687C, 0xFF3A6D9B, 0xFF3869B1, 0xFF3E6FCF,
    0xFF476415, 0xFF4D6933, 0xFF4B664A, 0xFF516B68, 0xFF4E687F, 0xFF546D9D, 0xFF526AB3, 0xFF586FD2,
    0xFF6A6C20, 0xFF676936, 0xFF6D6E55, 0xFF6B6B6B, 0xFF717089, 0xFF6F6DA0, 0xFF7472BE, 0xFF726FD5,
    0xFF846C22, 0xFF8A7141, 0xFF886E57, 0xFF8D7376, 0xFF8B708C, 0xFF9175AB, 0xFF8F72C1, 0xFF9577DF,
    0xFF9E6C25, 0xFFA47144, 0xFFA26E5A, 0xFFA87378, 0xFFA6708F, 0xFFAB75AD, 0xFFA972C4, 0xFFAF77E2,
    0xFFC17530, 0xFFBE7246, 0xFFC47765, 0xFFC2737B, 0xFFC8799A, 0xFFC675B0, 0xFFCC7ACE, 0xFFC977E5,
    0xFFDB7532, 0xFFE17A51, 0xFFDF7767, 0xFFE57C86, 0xFFE2799C, 0xFFE87EBB, 0xFFE67BD1, 0xFFEC80F0,
    0xFF137F0F, 0xFF19842E, 0xFF178144, 0xFF1D8663, 0xFF1A8379, 0xFF208898, 0xFF1E85AE, 0xFF248ACD,
    0xFF36881A, 0xFF338531, 0xFF398A4F, 0xFF378665, 0xFF3D8C84, 0xFF3B889A, 0xFF418DB9, 0xFF3E8ACF,
    0xFF50881D, 0xFF568D3B, 0xFF548A52, 0xFF598F70, 0xFF578C87, 0xFF5D91A5, 0xFF5B8EBB, 0xFF6193DA,
    0xFF6A8820, 0xFF708D3E, 0xFF6E8A54, 0xFF748F73, 0xFF728C89, 0xFF7791A8, 0xFF758EBE, 0xFF7B93DD,
    0xFF8D902A, 0xFF8B8D41, 0xFF90925F, 0xFF8E8F76, 0xFF949494, 0xFF9291AA, 0xFF9896C9, 0xFF9593DF,
    0xFFA7902D, 0xFFAD954C, 0xFFAB9262, 0xFFB19780, 0xFFAE9497, 0xFFB499B5, 0xFFB296CC, 0xFFB89BEA,
    0xFFC19030, 0xFFC7964E, 0xFFC59264, 0xFFCB9783, 0xFFC99499, 0xFFCF99B8, 0xFFCC96CE, 0xFFD29BED,
    0xFFE4993A, 0xFFE29651, 0xFFE89B6F, 0xFFE59886, 0xFFEB9DA4, 0xFFE999BA, 0xFFEF9ED9, 0xFFED9BEF,
    0xFF1CA317, 0xFF22A836, 0xFF20A54C, 0xFF26AA6B, 0xFF23A781, 0xFF29ACA0, 0xFF27A9B6, 0xFF25A6CC,
    0xFF36A31A, 0xFF3CA939, 0xFF3AA54F, 0xFF40AA6D, 0xFF3EA784, 0xFF43ACA2, 0xFF41A9B9, 0xFF47AED7,
    0xFF59AC25, 0xFF57A93B, 0xFF5CAE5A, 0xFF5AAB70, 0xFF60B08F, 0xFF5EACA5, 0xFF64B2C3, 0xFF61AEDA,
    0xFF73AC28, 0xFF79B146, 0xFF77AE5C, 0xFF7DB37B, 0xFF7AB091, 0xFF78ADA8, 0xFF7EB2C6, 0xFF7CAEDC,
    0xFF8DAC2A, 0xFF93B149, 0xFF91AE5F, 0xFF97B37E, 0xFF95B094, 0xFF9BB5B2, 0xFF98B2C9, 0xFF9EB7E7,
    0xFFB0B435, 0xFFAEB14B, 0xFFB4B66A, 0xFFB1B380, 0xFFB7B89F, 0xFFB5B5B5, 0xFFBBBAD4, 0xFFB9B7EA,
    0xFFCAB438, 0xFFD0B956, 0xFFCEB66C, 0xFFD4BB8B, 0xFFD2B8A1, 0xFFCFB5B8, 0xFFD5BAD6, 0xFFD3B7EC,
    0xFFE5B53A, 0xFFEBBA59, 0xFFE8B66F, 0xFFEEBB8E, 0xFFECB8A4, 0xFFF2BDC2, 0xFFF0BAD9, 0xFFF5BFF7,
    0xFF25C71F, 0xFF23C436, 0xFF28C954, 0xFF26C66B, 0xFF2CCB89, 0xFF2AC89F, 0xFF30CDBE, 0xFF2DCAD4,
    0xFF3FC722, 0xFF3DC438, 0xFF43C957, 0xFF40C66D, 0xFF46CB8C, 0xFF44C8A2, 0xFF4ACDC1, 0xFF48CAD7,
    0xFF59C825, 0xFF5FCD43, 0xFF5DC959, 0xFF63CF78, 0xFF61CB8E, 0xFF67D0AD, 0xFF64CDC3, 0xFF6AD2E2,
    0xFF7CD02F, 0xFF7ACD46, 0xFF80D264, 0xFF7DCF7B, 0xFF83D499, 0xFF81D1AF, 0xFF87D6CE, 0xFF85D2E4,
    0xFF96D032, 0xFF94CD48, 0xFF9AD267, 0xFF98CF7D, 0xFF9ED49C, 0xFF9BD1B2, 0xFFA1D6D1, 0xFF9FD3E7,
    0xFFB1D035, 0xFFB7D553, 0xFFB4D26A, 0xFFBAD788, 0xFFB8D49E, 0xFFBED9BD, 0xFFBCD6D3, 0xFFC1DBF2,
    0xFFD3D840, 0xFFD1D556, 0xFFD7DA74, 0xFFD5D78B, 0xFFDADCA9, 0xFFD8D9C0, 0xFFDEDEDE, 0xFFDCDBF4,
    0xFFEED842, 0xFFEBD558, 0xFFF1DA77, 0xFFEFD78D, 0xFFF5DCAC, 0xFFF2D9C2, 0xFFF8DEE1, 0xFFF6DBF7,
    0xFF25E31F, 0xFF2BE83E, 0xFF29E554, 0xFF2FEA73, 0xFF2DE789, 0xFF33ECA7, 0xFF30E9BE, 0xFF36EEDC,
    0xFF48EB2A, 0xFF46E840, 0xFF4CED5F, 0xFF49EA75, 0xFF4FEF94, 0xFF4DECAA, 0xFF53F1C9, 0xFF51EEDF,
    0xFF62EC2D, 0xFF60E843, 0xFF66ED61, 0xFF64EA78, 0xFF6AEF96, 0xFF67ECAD, 0xFF6DF1CB, 0xFF6BEEE1,
    0xFF7DEC2F, 0xFF83F14E, 0xFF80EE64, 0xFF86F383, 0xFF84EF99, 0xFF8AF4B7, 0xFF88F1CE, 0xFF8DF6EC,
    0xFF9FF43A, 0xFF9DF150, 0xFFA3F66F, 0xFFA1F385, 0xFFA6F8A4, 0xFFA4F5BA, 0xFFAAFAD9, 0xFFA8F6EF,
    0xFFBAF43D, 0xFFB7F153, 0xFFBDF672, 0xFFBBF388, 0xFFC1F8A6, 0xFFBFF5BD, 0xFFC4FADB, 0xFFC2F7F2,
    0xFFD4F43F, 0xFFDAF95E, 0xFFD7F674, 0xFFDDFB93, 0xFFDBF8A9, 0xFFE1FDC8, 0xFFDFFADE, 0xFFE5FFFC,
    0xFFF6FC4A, 0xFFF4F960, 0xFFFAFE7F, 0xFFF8FB95, 0xFFFEFFB4, 0xFFFBFDCA, 0xFFFFFFE9, 0xFFFFFFFF
};

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_initCore(JNIEnv* env, jobject /* this */) {
    if (!g_emu) {
        try {
            if (FolderUtilities::GetHomeFolder().empty()) {
                FolderUtilities::SetHomeFolder("/data/data/com.temulator.app/files");
            }
        } catch (...) {
        }

        g_emu = make_unique<Emulator>();
        g_emu->Initialize();
        
        g_renderer = make_unique<AndroidRenderingDevice>(g_emu.get());
        g_audio = make_unique<AndroidAudioDevice>(g_emu.get());
        g_keyManager = make_unique<AndroidKeyManager>();
        KeyManager::RegisterKeyManager(g_keyManager.get());
        KeyManager::SetSettings(g_emu->GetSettings());

        // Explicitly enable and configure global audio settings
        AudioConfig audioCfg = g_emu->GetSettings()->GetAudioConfig();
        audioCfg.EnableAudio = true;
        audioCfg.MasterVolume = 100;
        audioCfg.SampleRate = 48000;
        audioCfg.AudioLatency = 60;
        audioCfg.MuteSoundInBackground = false;
        audioCfg.ReduceSoundInBackground = false;
        g_emu->GetSettings()->SetAudioConfig(audioCfg);

        // Configure default key bindings for NES
        NesConfig nesCfg = g_emu->GetSettings()->GetNesConfig();
        nesCfg.Port1.Type = ControllerType::NesController;
        nesCfg.Port1.Keys.Mapping1.Up = 1;
        nesCfg.Port1.Keys.Mapping1.Down = 2;
        nesCfg.Port1.Keys.Mapping1.Left = 3;
        nesCfg.Port1.Keys.Mapping1.Right = 4;
        nesCfg.Port1.Keys.Mapping1.A = 5;
        nesCfg.Port1.Keys.Mapping1.B = 6;
        nesCfg.Port1.Keys.Mapping1.TurboA = 13;
        nesCfg.Port1.Keys.Mapping1.TurboB = 14;
        nesCfg.Port1.Keys.Mapping1.Select = 11;
        nesCfg.Port1.Keys.Mapping1.Start = 12;

        // Alternate mapping for 4-button controllers:
        // X = Turbo A (13 or 7), Y = Turbo B (14 or 8) or B
        nesCfg.Port1.Keys.Mapping2.TurboA = 7;
        nesCfg.Port1.Keys.Mapping2.TurboB = 8;
        nesCfg.Port1.Keys.Mapping2.B = 8;

        // Initialize NES UserPalette and ChannelVolumes
        memcpy(nesCfg.UserPalette, s_defaultNesPalette, sizeof(s_defaultNesPalette));
        nesCfg.IsFullColorPalette = false;
        for (int i = 0; i < 11; i++) {
            nesCfg.ChannelVolumes[i] = 100;
        }
        nesCfg.EpsmVolume = 100;
        g_emu->GetSettings()->SetNesConfig(nesCfg);

        // Configure default key bindings for SNES
        SnesConfig snesCfg = g_emu->GetSettings()->GetSnesConfig();
        snesCfg.Port1.Type = ControllerType::SnesController;
        snesCfg.Port1.Keys.Mapping1.Up = 1;
        snesCfg.Port1.Keys.Mapping1.Down = 2;
        snesCfg.Port1.Keys.Mapping1.Left = 3;
        snesCfg.Port1.Keys.Mapping1.Right = 4;
        snesCfg.Port1.Keys.Mapping1.A = 5;
        snesCfg.Port1.Keys.Mapping1.B = 6;
        snesCfg.Port1.Keys.Mapping1.X = 7;
        snesCfg.Port1.Keys.Mapping1.Y = 8;
        snesCfg.Port1.Keys.Mapping1.L = 9;
        snesCfg.Port1.Keys.Mapping1.R = 10;
        snesCfg.Port1.Keys.Mapping1.TurboA = 13;
        snesCfg.Port1.Keys.Mapping1.TurboB = 14;
        snesCfg.Port1.Keys.Mapping1.Select = 11;
        snesCfg.Port1.Keys.Mapping1.Start = 12;
        snesCfg.DisableFrameSkipping = false;
        for (int i = 0; i < 8; i++) {
            snesCfg.ChannelVolumes[i] = 100;
        }
        g_emu->GetSettings()->SetSnesConfig(snesCfg);

        // Configure Gameboy
        GameboyConfig gbCfg = g_emu->GetSettings()->GetGameboyConfig();
        gbCfg.Controller.Type = ControllerType::GameboyController;
        gbCfg.Controller.Keys.Mapping1.Up = 1;
        gbCfg.Controller.Keys.Mapping1.Down = 2;
        gbCfg.Controller.Keys.Mapping1.Left = 3;
        gbCfg.Controller.Keys.Mapping1.Right = 4;
        gbCfg.Controller.Keys.Mapping1.A = 5;
        gbCfg.Controller.Keys.Mapping1.B = 6;
        gbCfg.Controller.Keys.Mapping1.TurboA = 13;
        gbCfg.Controller.Keys.Mapping1.TurboB = 14;
        gbCfg.Controller.Keys.Mapping1.Select = 11;
        gbCfg.Controller.Keys.Mapping1.Start = 12;
        gbCfg.Controller.Keys.Mapping2.TurboA = 7;
        gbCfg.Controller.Keys.Mapping2.TurboB = 8;
        gbCfg.Square1Vol = 100;
        gbCfg.Square2Vol = 100;
        gbCfg.NoiseVol = 100;
        gbCfg.WaveVol = 100;
        g_emu->GetSettings()->SetGameboyConfig(gbCfg);

        // Configure GBA
        GbaConfig gbaCfg = g_emu->GetSettings()->GetGbaConfig();
        gbaCfg.SkipBootScreen = true;
        gbaCfg.Controller.Type = ControllerType::GbaController;
        gbaCfg.Controller.Keys.Mapping1.Up = 1;
        gbaCfg.Controller.Keys.Mapping1.Down = 2;
        gbaCfg.Controller.Keys.Mapping1.Left = 3;
        gbaCfg.Controller.Keys.Mapping1.Right = 4;
        gbaCfg.Controller.Keys.Mapping1.A = 5;
        gbaCfg.Controller.Keys.Mapping1.B = 6;
        gbaCfg.Controller.Keys.Mapping1.L = 9;
        gbaCfg.Controller.Keys.Mapping1.R = 10;
        gbaCfg.Controller.Keys.Mapping1.TurboA = 13;
        gbaCfg.Controller.Keys.Mapping1.TurboB = 14;
        gbaCfg.Controller.Keys.Mapping1.Select = 11;
        gbaCfg.Controller.Keys.Mapping1.Start = 12;
        gbaCfg.Controller.Keys.Mapping2.TurboA = 7;
        gbaCfg.Controller.Keys.Mapping2.TurboB = 8;
        gbaCfg.DisableFrameSkipping = false;
        gbaCfg.ChannelAVol = 100;
        gbaCfg.ChannelBVol = 100;
        gbaCfg.Square1Vol = 100;
        gbaCfg.Square2Vol = 100;
        gbaCfg.NoiseVol = 100;
        gbaCfg.WaveVol = 100;
        g_emu->GetSettings()->SetGbaConfig(gbaCfg);

        // Ensure flags are clean and mobile rewind buffer size is moderate (60MB)
        g_emu->GetSettings()->ClearFlag(EmulationFlags::Rewind);
        g_emu->GetSettings()->ClearFlag(EmulationFlags::Turbo);
        g_emu->GetSettings()->GetPreferences().RewindBufferSize = 60;

        // Configure PC Engine
        PcEngineConfig pceCfg = g_emu->GetSettings()->GetPcEngineConfig();
        pceCfg.Port1.Type = ControllerType::PceController;
        pceCfg.Port1.Keys.Mapping1.Up = 1;
        pceCfg.Port1.Keys.Mapping1.Down = 2;
        pceCfg.Port1.Keys.Mapping1.Left = 3;
        pceCfg.Port1.Keys.Mapping1.Right = 4;
        pceCfg.Port1.Keys.Mapping1.A = 5;       // Button I
        pceCfg.Port1.Keys.Mapping1.B = 6;       // Button II
        pceCfg.Port1.Keys.Mapping1.TurboA = 13; // Turbo I
        pceCfg.Port1.Keys.Mapping1.TurboB = 14; // Turbo II
        pceCfg.Port1.Keys.Mapping1.Select = 11; // Select
        pceCfg.Port1.Keys.Mapping1.Start = 12;  // Run
        pceCfg.Port1.Keys.Mapping2.TurboA = 7;  // X = Turbo I
        pceCfg.Port1.Keys.Mapping2.TurboB = 8;  // Y = Turbo II
        memcpy(pceCfg.Palette, s_defaultPcePalette, sizeof(s_defaultPcePalette));
        for (int i = 0; i < 6; i++) {
            pceCfg.ChannelVol[i] = 100;
        }
        pceCfg.CdAudioVolume = 100;
        pceCfg.AdpcmVolume = 100;
        pceCfg.UseHuC6280aAudio = true;
        g_emu->GetSettings()->SetPcEngineConfig(pceCfg);

        // Configure SMS / Game Gear
        SmsConfig smsCfg = g_emu->GetSettings()->GetSmsConfig();
        smsCfg.Port1.Type = ControllerType::SmsController;
        smsCfg.Port1.Keys.Mapping1.Up = 1;
        smsCfg.Port1.Keys.Mapping1.Down = 2;
        smsCfg.Port1.Keys.Mapping1.Left = 3;
        smsCfg.Port1.Keys.Mapping1.Right = 4;
        smsCfg.Port1.Keys.Mapping1.B = 6;       // Button 1
        smsCfg.Port1.Keys.Mapping1.A = 5;       // Button 2
        smsCfg.Port1.Keys.Mapping1.TurboB = 14; // Turbo 1
        smsCfg.Port1.Keys.Mapping1.TurboA = 13; // Turbo 2
        smsCfg.Port1.Keys.Mapping1.Start = 12;  // Pause / Start
        smsCfg.Port1.Keys.Mapping1.Select = 11;
        smsCfg.Port1.Keys.Mapping2.TurboB = 8;  // Y = Turbo 1
        smsCfg.Port1.Keys.Mapping2.TurboA = 7;  // X = Turbo 2
        for (int i = 0; i < 4; i++) {
            smsCfg.ChannelVolumes[i] = 100;
        }
        smsCfg.FmAudioVolume = 100;
        smsCfg.EnableFmAudio = true;
        g_emu->GetSettings()->SetSmsConfig(smsCfg);

        // Configure ColecoVision
        CvConfig cvCfg = g_emu->GetSettings()->GetCvConfig();
        cvCfg.Port1.Type = ControllerType::ColecoVisionController;
        cvCfg.Port1.Keys.Mapping1.Up = 1;
        cvCfg.Port1.Keys.Mapping1.Down = 2;
        cvCfg.Port1.Keys.Mapping1.Left = 3;
        cvCfg.Port1.Keys.Mapping1.Right = 4;
        cvCfg.Port1.Keys.Mapping1.CustomKeys[0] = 1; // Up
        cvCfg.Port1.Keys.Mapping1.CustomKeys[1] = 2; // Down
        cvCfg.Port1.Keys.Mapping1.CustomKeys[2] = 3; // Left
        cvCfg.Port1.Keys.Mapping1.CustomKeys[3] = 4; // Right
        cvCfg.Port1.Keys.Mapping1.CustomKeys[4] = 6; // L button (B)
        cvCfg.Port1.Keys.Mapping1.CustomKeys[5] = 5; // R button (A)
        for (int i = 0; i < 4; i++) {
            cvCfg.ChannelVolumes[i] = 100;
        }
        g_emu->GetSettings()->SetCvConfig(cvCfg);

        // Configure WonderSwan
        WsConfig wsCfg = g_emu->GetSettings()->GetWsConfig();
        wsCfg.ControllerHorizontal.Type = ControllerType::WsController;
        wsCfg.ControllerHorizontal.Keys.Mapping1.Up = 1;    // X1
        wsCfg.ControllerHorizontal.Keys.Mapping1.Down = 2;  // X3
        wsCfg.ControllerHorizontal.Keys.Mapping1.Left = 3;  // X4
        wsCfg.ControllerHorizontal.Keys.Mapping1.Right = 4; // X2
        wsCfg.ControllerHorizontal.Keys.Mapping1.A = 5;     // A
        wsCfg.ControllerHorizontal.Keys.Mapping1.B = 6;     // B
        wsCfg.ControllerHorizontal.Keys.Mapping1.TurboA = 13;
        wsCfg.ControllerHorizontal.Keys.Mapping1.TurboB = 14;
        wsCfg.ControllerHorizontal.Keys.Mapping1.Start = 12; // Start
        wsCfg.ControllerHorizontal.Keys.Mapping1.GenericKey1 = 11; // Sound
        wsCfg.ControllerHorizontal.Keys.Mapping2.TurboA = 7;
        wsCfg.ControllerHorizontal.Keys.Mapping2.TurboB = 8;

        wsCfg.ControllerVertical.Type = ControllerType::WsControllerVertical;
        wsCfg.ControllerVertical.Keys.Mapping1.Up = 1;
        wsCfg.ControllerVertical.Keys.Mapping1.Down = 2;
        wsCfg.ControllerVertical.Keys.Mapping1.Left = 3;
        wsCfg.ControllerVertical.Keys.Mapping1.Right = 4;
        wsCfg.ControllerVertical.Keys.Mapping1.A = 5;
        wsCfg.ControllerVertical.Keys.Mapping1.B = 6;
        wsCfg.ControllerVertical.Keys.Mapping1.TurboA = 13;
        wsCfg.ControllerVertical.Keys.Mapping1.TurboB = 14;
        wsCfg.ControllerVertical.Keys.Mapping1.Start = 12;
        wsCfg.ControllerVertical.Keys.Mapping1.GenericKey1 = 11;

        wsCfg.Channel1Vol = 100;
        wsCfg.Channel2Vol = 100;
        wsCfg.Channel3Vol = 100;
        wsCfg.Channel4Vol = 100;
        wsCfg.Channel5Vol = 100;
        g_emu->GetSettings()->SetWsConfig(wsCfg);

        LOGI("All emulator cores initialized with controllers, audio channels, and palettes.");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_initCoreWithFolder(JNIEnv* env, jobject /* this */, jstring homeFolder) {
    if (homeFolder) {
        const char* folderPath = env->GetStringUTFChars(homeFolder, 0);
        try {
            FolderUtilities::SetHomeFolder(folderPath);
        } catch (...) {
        }
        env->ReleaseStringUTFChars(homeFolder, folderPath);
    }
    Java_com_temulator_app_EmulatorNative_initCore(env, nullptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_setFolderOverrides(JNIEnv* env, jobject /* this */, jstring saveFolder, jstring saveStateFolder) {
    std::string sFolder = "";
    std::string ssFolder = "";
    if (saveFolder) {
        const char* cStr = env->GetStringUTFChars(saveFolder, 0);
        sFolder = cStr ? cStr : "";
        env->ReleaseStringUTFChars(saveFolder, cStr);
    }
    if (saveStateFolder) {
        const char* cStr = env->GetStringUTFChars(saveStateFolder, 0);
        ssFolder = cStr ? cStr : "";
        env->ReleaseStringUTFChars(saveStateFolder, cStr);
    }
    try {
        FolderUtilities::SetFolderOverrides(sFolder, ssFolder, "", "");
        LOGI("Folder overrides configured: saves=%s, states=%s", sFolder.c_str(), ssFolder.c_str());
    } catch (...) {
        LOGE("Failed to set folder overrides");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_temulator_app_EmulatorNative_loadRom(JNIEnv* env, jobject /* this */, jstring path, jint consoleType) {
    if (!g_emu) return JNI_FALSE;

    const char* nativePath = env->GetStringUTFChars(path, 0);
    LOGI("Loading ROM: %s, consoleType=%d", nativePath, consoleType);

    // Ensure GBA skip boot screen and frame skipping are properly configured
    GbaConfig gbaCfg = g_emu->GetSettings()->GetGbaConfig();
    gbaCfg.SkipBootScreen = true;
    gbaCfg.DisableFrameSkipping = false;
    g_emu->GetSettings()->SetGbaConfig(gbaCfg);

    SnesConfig snesCfg = g_emu->GetSettings()->GetSnesConfig();
    snesCfg.DisableFrameSkipping = false;
    g_emu->GetSettings()->SetSnesConfig(snesCfg);

    // Make sure rewind runtime flag is cleared upon loading a new ROM
    g_emu->GetSettings()->ClearFlag(EmulationFlags::Rewind);

    string filePath(nativePath);
    bool success = false;

    // Check if the file is an archive (.zip, .7z)
    unique_ptr<ArchiveReader> reader = ArchiveReader::GetReader(filePath);
    if (reader) {
        vector<string> romFiles = reader->GetFileList(VirtualFile::RomExtensions);

        // Prioritize file matching the selected consoleType
        if (!romFiles.empty() && consoleType >= 0 && consoleType <= 6) {
            std::stable_partition(romFiles.begin(), romFiles.end(), [consoleType](const string& name) {
                string ext = FolderUtilities::GetExtension(name);
                std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
                switch (consoleType) {
                    case 0: return ext == ".sfc" || ext == ".smc" || ext == ".fig";
                    case 1: return ext == ".gb" || ext == ".gbc" || ext == ".sgb";
                    case 2: return ext == ".nes" || ext == ".fds" || ext == ".unf";
                    case 3: return ext == ".pce" || ext == ".sgx";
                    case 4: return ext == ".sms" || ext == ".gg" || ext == ".col";
                    case 5: return ext == ".gba" || ext == ".agb" || ext == ".bin";
                    case 6: return ext == ".ws" || ext == ".wsc";
                    default: return false;
                }
            });
        }

        // Try loading each ROM file until one succeeds
        for (const string& romName : romFiles) {
            LOGI("Attempting archive ROM: %s", romName.c_str());
            VirtualFile vFile(filePath, romName);
            if (g_emu->LoadRom(vFile, VirtualFile(), true, false)) {
                success = true;
                break;
            }
        }

        // Fallback: if romFiles was empty, check all files inside archive
        if (!success && romFiles.empty()) {
            vector<string> allFiles = reader->GetFileList({});
            for (const string& name : allFiles) {
                string ext = FolderUtilities::GetExtension(name);
                std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
                if (ext != ".txt" && ext != ".nfo" && ext != ".doc" && ext != ".jpg" && ext != ".png" && ext != ".pdf") {
                    LOGI("Attempting fallback candidate: %s", name.c_str());
                    VirtualFile vFile(filePath, name);
                    if (g_emu->LoadRom(vFile, VirtualFile(), true, false)) {
                        success = true;
                        break;
                    }
                }
            }
        }
    }

    if (!success) {
        VirtualFile vFile(filePath);
        success = g_emu->LoadRom(vFile, VirtualFile(), true, false);
    }

    LOGI("LoadRom result: %s", success ? "SUCCESS" : "FAILED");
    env->ReleaseStringUTFChars(path, nativePath);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_temulator_app_EmulatorNative_getConsoleType(JNIEnv* env, jobject /* this */) {
    if (!g_emu) return 0;
    return (jint)g_emu->GetConsoleType();
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_setKeyState(JNIEnv* env, jobject /* this */, jint keyCode, jboolean isPressed) {
    if (g_keyManager) {
        g_keyManager->SetKeyState((uint16_t)keyCode, isPressed);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_temulator_app_EmulatorNative_runFrame(JNIEnv* env, jobject /* this */, jobject bitmap) {
    if (!g_emu || !g_renderer) return 0;

    std::lock_guard<std::mutex> lock(g_renderer->bufferMutex);
    if (g_renderer->frameCopy.empty() || !bitmap) return 0;

    AndroidBitmapInfo info;
    void* pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return 0;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return 0;
    
    uint32_t width = min((uint32_t)info.width, g_renderer->latestWidth);
    uint32_t height = min((uint32_t)info.height, g_renderer->latestHeight);
    
    const uint32_t* src = g_renderer->frameCopy.data();
    uint8_t* dstBytes = reinterpret_cast<uint8_t*>(pixels);
    
    for (uint32_t y = 0; y < height; ++y) {
        memcpy(dstBytes + y * info.stride, src + y * g_renderer->latestWidth, width * 4);
    }
    
    AndroidBitmap_unlockPixels(env, bitmap);
    return (jint)((g_renderer->latestWidth << 16) | (g_renderer->latestHeight & 0xFFFF));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_temulator_app_EmulatorNative_getFrameWidth(JNIEnv* env, jobject /* this */) {
    if (!g_renderer) return 256;
    std::lock_guard<std::mutex> lock(g_renderer->bufferMutex);
    return (jint)(g_renderer->latestWidth > 0 ? g_renderer->latestWidth : 256);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_temulator_app_EmulatorNative_getFrameHeight(JNIEnv* env, jobject /* this */) {
    if (!g_renderer) return 240;
    std::lock_guard<std::mutex> lock(g_renderer->bufferMutex);
    return (jint)(g_renderer->latestHeight > 0 ? g_renderer->latestHeight : 240);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_temulator_app_EmulatorNative_getFps(JNIEnv* env, jobject /* this */) {
    if (!g_emu) return 60.0;
    return g_emu->GetFps();
}

// --- Save & Load State ---
extern "C" JNIEXPORT jboolean JNICALL
Java_com_temulator_app_EmulatorNative_saveState(JNIEnv* env, jobject /* this */, jint slot) {
    if (!g_emu) return JNI_FALSE;
    try {
        g_emu->GetSaveStateManager()->SaveState((int)slot, false);
        return JNI_TRUE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_temulator_app_EmulatorNative_loadState(JNIEnv* env, jobject /* this */, jint slot) {
    if (!g_emu) return JNI_FALSE;
    try {
        return g_emu->GetSaveStateManager()->LoadState((int)slot) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_temulator_app_EmulatorNative_hasSaveState(JNIEnv* env, jobject /* this */, jint slot) {
    if (!g_emu) return JNI_FALSE;
    string romFile = g_emu->GetRomInfo().RomFile.GetFileName();
    if (romFile.empty()) return JNI_FALSE;
    string folder = FolderUtilities::GetSaveStateFolder();
    string filename = FolderUtilities::GetFilename(romFile, false) + "_" + std::to_string(slot) + ".mss";
    string path = FolderUtilities::CombinePath(folder, filename);
    FILE* f = fopen(path.c_str(), "rb");
    if (f) {
        fclose(f);
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_resetGame(JNIEnv* env, jobject /* this */) {
    if (g_emu) {
        g_emu->Reset();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_powerCycleGame(JNIEnv* env, jobject /* this */) {
    if (g_emu) {
        g_emu->PowerCycle();
    }
}

// --- Display & Video Settings ---
extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_setVideoFilter(JNIEnv* env, jobject /* this */, jint filterType) {
    if (!g_emu) return;
    VideoConfig cfg = g_emu->GetSettings()->GetVideoConfig();
    cfg.VideoFilter = (VideoFilterType)filterType;
    g_emu->GetSettings()->SetVideoConfig(cfg);
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_setAspectRatio(JNIEnv* env, jobject /* this */, jint aspectRatio, jdouble customRatio) {
    if (!g_emu) return;
    VideoConfig cfg = g_emu->GetSettings()->GetVideoConfig();
    cfg.AspectRatio = (VideoAspectRatio)aspectRatio;
    if (customRatio > 0.0) {
        cfg.CustomAspectRatio = customRatio;
    }
    g_emu->GetSettings()->SetVideoConfig(cfg);
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_setBilinearInterpolation(JNIEnv* env, jobject /* this */, jboolean enabled) {
    if (!g_emu) return;
    VideoConfig cfg = g_emu->GetSettings()->GetVideoConfig();
    cfg.UseBilinearInterpolation = enabled;
    g_emu->GetSettings()->SetVideoConfig(cfg);
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_setScanlineIntensity(JNIEnv* env, jobject /* this */, jint intensity) {
    if (!g_emu) return;
    VideoConfig cfg = g_emu->GetSettings()->GetVideoConfig();
    cfg.ScanlineIntensity = (double)intensity;
    g_emu->GetSettings()->SetVideoConfig(cfg);
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_setColorAdjustments(JNIEnv* env, jobject /* this */, jdouble brightness, jdouble contrast, jdouble saturation, jdouble hue) {
    if (!g_emu) return;
    VideoConfig cfg = g_emu->GetSettings()->GetVideoConfig();
    cfg.Brightness = brightness;
    cfg.Contrast = contrast;
    cfg.Saturation = saturation;
    cfg.Hue = hue;
    g_emu->GetSettings()->SetVideoConfig(cfg);
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_setScreenRotation(JNIEnv* env, jobject /* this */, jint rotation) {
    if (!g_emu) return;
    VideoConfig cfg = g_emu->GetSettings()->GetVideoConfig();
    cfg.ScreenRotation = (uint32_t)rotation;
    g_emu->GetSettings()->SetVideoConfig(cfg);
}

// --- Emulation & Speed Settings ---
extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_setEmulationSpeed(JNIEnv* env, jobject /* this */, jint speedPercent) {
    if (!g_emu) return;
    EmulationConfig cfg = g_emu->GetSettings()->GetEmulationConfig();
    cfg.EmulationSpeed = (uint32_t)max(10, speedPercent);
    g_emu->GetSettings()->SetEmulationConfig(cfg);
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_setTurbo(JNIEnv* env, jobject /* this */, jboolean enabled) {
    if (!g_emu) return;
    g_emu->GetSettings()->SetFlagState(EmulationFlags::Turbo, enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_setRewind(JNIEnv* env, jobject /* this */, jboolean enabled) {
    if (!g_emu) return;
    // Set RewindBufferSize in preferences (0 = disabled, 60MB = enabled)
    // NEVER set EmulationFlags::Rewind! That flag is only for active backward playback!
    // Setting EmulationFlags::Rewind here was locking GetEmulationSpeed() permanently to 100%.
    g_emu->GetSettings()->GetPreferences().RewindBufferSize = enabled ? 60 : 0;
    g_emu->GetSettings()->ClearFlag(EmulationFlags::Rewind);
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_setRegion(JNIEnv* env, jobject /* this */, jint region) {
    if (!g_emu) return;
    SnesConfig snes = g_emu->GetSettings()->GetSnesConfig();
    snes.Region = (ConsoleRegion)region;
    g_emu->GetSettings()->SetSnesConfig(snes);

    NesConfig nes = g_emu->GetSettings()->GetNesConfig();
    nes.Region = (ConsoleRegion)region;
    g_emu->GetSettings()->SetNesConfig(nes);

    SmsConfig sms = g_emu->GetSettings()->GetSmsConfig();
    sms.Region = (ConsoleRegion)region;
    sms.GameGearRegion = (ConsoleRegion)region;
    g_emu->GetSettings()->SetSmsConfig(sms);
}

// --- Audio Settings ---
extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_setAudioConfig(JNIEnv* env, jobject /* this */, jboolean enabled, jint volume, jint sampleRate, jboolean reverb) {
    if (!g_emu) return;
    AudioConfig cfg = g_emu->GetSettings()->GetAudioConfig();
    cfg.EnableAudio = enabled;
    cfg.MasterVolume = (uint32_t)clamp(volume, 0, 100);
    if (sampleRate > 0) cfg.SampleRate = (uint32_t)sampleRate;
    cfg.ReverbEnabled = reverb;
    g_emu->GetSettings()->SetAudioConfig(cfg);
}

// --- System Specific Settings ---
extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_setSystemConfig(JNIEnv* env, jobject /* this */, jboolean removeSpriteLimit, jboolean blendFrames, jboolean gbaColorCorrection, jint gbModel) {
    if (!g_emu) return;
    // NES
    NesConfig nes = g_emu->GetSettings()->GetNesConfig();
    nes.RemoveSpriteLimit = removeSpriteLimit;
    g_emu->GetSettings()->SetNesConfig(nes);

    // Gameboy
    GameboyConfig gb = g_emu->GetSettings()->GetGameboyConfig();
    gb.BlendFrames = blendFrames;
    if (gbModel >= 0 && gbModel <= 5) {
        gb.Model = (GameboyModel)gbModel;
    }
    g_emu->GetSettings()->SetGameboyConfig(gb);

    // GBA
    GbaConfig gba = g_emu->GetSettings()->GetGbaConfig();
    gba.BlendFrames = blendFrames;
    gba.GbaAdjustColors = gbaColorCorrection;
    g_emu->GetSettings()->SetGbaConfig(gba);

    // PC Engine
    PcEngineConfig pce = g_emu->GetSettings()->GetPcEngineConfig();
    pce.RemoveSpriteLimit = removeSpriteLimit;
    g_emu->GetSettings()->SetPcEngineConfig(pce);

    // SMS / Game Gear
    SmsConfig sms = g_emu->GetSettings()->GetSmsConfig();
    sms.RemoveSpriteLimit = removeSpriteLimit;
    sms.GgBlendFrames = blendFrames;
    g_emu->GetSettings()->SetSmsConfig(sms);

    // WonderSwan
    WsConfig ws = g_emu->GetSettings()->GetWsConfig();
    ws.BlendFrames = blendFrames;
    g_emu->GetSettings()->SetWsConfig(ws);
}

// --- Battery Save / Stop Emulation ---
extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_pauseEmulation(JNIEnv* env, jobject /* this */) {
    if (g_audio) {
        g_audio->Pause();
    }
    if (g_emu) {
        g_emu->Pause();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_resumeEmulation(JNIEnv* env, jobject /* this */) {
    if (g_emu) {
        g_emu->Resume();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_saveBattery(JNIEnv* env, jobject /* this */) {
    if (g_emu) {
        auto lock = g_emu->AcquireLock();
        shared_ptr<IConsole> console = g_emu->GetConsole();
        if (console) {
            console->SaveBattery();
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_stopEmulation(JNIEnv* env, jobject /* this */) {
    if (g_audio) {
        g_audio->Stop();
    }
    if (g_emu) {
        LOGI("Stopping emulation and flushing SRAM battery saves.");
        g_emu->Stop(true);
    }
}

// --- Cheat Manager ---
extern "C" JNIEXPORT jboolean JNICALL
Java_com_temulator_app_EmulatorNative_addCheat(JNIEnv* env, jobject /* this */, jint type, jstring code) {
    if (!g_emu) return JNI_FALSE;
    const char* nativeCode = env->GetStringUTFChars(code, 0);
    CheatCode cc = {};
    cc.Type = (CheatType)type;
    strncpy(cc.Code, nativeCode, sizeof(cc.Code) - 1);
    env->ReleaseStringUTFChars(code, nativeCode);
    return g_emu->GetCheatManager()->AddCheat(cc) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_clearCheats(JNIEnv* env, jobject /* this */) {
    if (g_emu) {
        g_emu->GetCheatManager()->ClearCheats();
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_temulator_app_EmulatorNative_getCheatCount(JNIEnv* env, jobject /* this */) {
    if (!g_emu) return 0;
    return (jint)g_emu->GetCheatManager()->GetCheats().size();
}

// --- In-Game Rewind ---
extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_startRewind(JNIEnv* env, jobject /* this */) {
    if (g_emu && g_emu->GetRewindManager()) {
        g_emu->GetSettings()->SetFlagState(EmulationFlags::Rewind, true);
        g_emu->GetRewindManager()->StartRewinding();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_stopRewind(JNIEnv* env, jobject /* this */) {
    if (g_emu && g_emu->GetRewindManager()) {
        g_emu->GetRewindManager()->StopRewinding();
        g_emu->GetSettings()->SetFlagState(EmulationFlags::Rewind, false);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_rewindSeconds(JNIEnv* env, jobject /* this */, jint seconds) {
    if (g_emu && g_emu->GetRewindManager()) {
        g_emu->GetRewindManager()->RewindSeconds((uint32_t)seconds);
    }
}

// --- Palettes ---
extern "C" JNIEXPORT void JNICALL
Java_com_temulator_app_EmulatorNative_setGameboyPalette(JNIEnv* env, jobject /* this */, jint palType) {
    if (!g_emu) return;
    GameboyConfig gb = g_emu->GetSettings()->GetGameboyConfig();
    if (palType == 1) { // DMG Green
        uint32_t green[4] = { 0x9BBC0F, 0x8BAC0F, 0x306230, 0x0F380F };
        memcpy(gb.BgColors, green, sizeof(green));
        memcpy(gb.Obj0Colors, green, sizeof(green));
        memcpy(gb.Obj1Colors, green, sizeof(green));
    } else if (palType == 2) { // Pocket Gray
        uint32_t gray[4] = { 0xFFFFFF, 0xAAAAAA, 0x555555, 0x000000 };
        memcpy(gb.BgColors, gray, sizeof(gray));
        memcpy(gb.Obj0Colors, gray, sizeof(gray));
        memcpy(gb.Obj1Colors, gray, sizeof(gray));
    } else if (palType == 3) { // Game Boy Light (Teal)
        uint32_t teal[4] = { 0x00EECC, 0x00AA99, 0x006655, 0x002211 };
        memcpy(gb.BgColors, teal, sizeof(teal));
        memcpy(gb.Obj0Colors, teal, sizeof(teal));
        memcpy(gb.Obj1Colors, teal, sizeof(teal));
    } else { // Original
        uint32_t orig[4] = { 0xFFFFFF, 0xB0B0B0, 0x686868, 0x000000 };
        memcpy(gb.BgColors, orig, sizeof(orig));
        memcpy(gb.Obj0Colors, orig, sizeof(orig));
        memcpy(gb.Obj1Colors, orig, sizeof(orig));
    }
    g_emu->GetSettings()->SetGameboyConfig(gb);
}


