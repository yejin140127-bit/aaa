package com.example.obanmod;

import com.google.gson.JsonParser;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OBanVoicePlugin implements VoicechatPlugin {

    private static final boolean USE_PARTIAL = true;

    private static volatile Model model;
    private volatile VoicechatServerApi serverApi;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public String getPluginId() {
        return "oban_voice";
    }

    @Override
    public void initialize(VoicechatApi api) {
        Thread t = new Thread(() -> {
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS);
                Path path = FabricLoader.getInstance().getGameDir().resolve("oban_model");
                model = new Model(path.toString());
                System.out.println("[OBan] 음성 인식 모델 로드 완료");
            } catch (Throwable e) {
                System.err.println("[OBan] 모델 로드 실패: 게임폴더/oban_model 폴더를 확인하세요.");
                e.printStackTrace();
            }
        }, "oban-model-loader");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, e -> serverApi = e.getVoicechat());
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
        registration.registerEvent(PlayerDisconnectedEvent.class, e -> {
            Session s = sessions.remove(e.getPlayerUuid());
            if (s != null) s.close();
        });
    }

    private void onMicrophone(MicrophonePacketEvent event) {
        if (model == null || serverApi == null) return;

        VoicechatConnection connection = event.getSenderConnection();
        if (connection == null) return;
        if (!(connection.getPlayer().getPlayer() instanceof ServerPlayerEntity player)) return;

        byte[] opus = event.getPacket().getOpusEncodedData();

        Session session = sessions.computeIfAbsent(player.getUuid(), id -> {
            try {
                return new Session(serverApi.createDecoder(), new Recognizer(model, 16000f));
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        session.submit(opus, player);
    }

    private static class Session {
        private final OpusDecoder decoder;
        private final Recognizer recognizer;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "oban-stt");
            t.setDaemon(true);
            return t;
        });

        Session(OpusDecoder decoder, Recognizer recognizer) {
            this.decoder = decoder;
            this.recognizer = recognizer;
        }

        void submit(byte[] opus, ServerPlayerEntity player) {
            executor.execute(() -> {
                try {
                    String text;
                    if (opus == null || opus.length == 0) {
                        decoder.resetState();
                        text = parse(recognizer.getFinalResult(), "text");
                    } else {
                        short[] pcm = decoder.decode(opus);
                        byte[] data = downsampleTo16k(pcm);
                        if (recognizer.acceptWaveForm(data, data.length)) {
                            text = parse(recognizer.getResult(), "text");
                        } else if (USE_PARTIAL) {
                            text = parse(recognizer.getPartialResult(), "partial");
                        } else {
                            return;
                        }
                    }

                    if (text.isBlank() || !player.isAlive()) return;

                    Character bad = findIeung(text);
                    if (bad != null) {
                        recognizer.reset();
                        punish(player, bad, text);
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            });
        }

        void close() {
            executor.execute(() -> {
                decoder.close();
                recognizer.close();
            });
            executor.shutdown();
        }
    }

    private static void punish(ServerPlayerEntity player, char bad, String heard) {
        var server = player.getServer();
        if (server == null) return;
        server.execute(() -> {
            if (!player.isAlive()) return;
            server.getPlayerManager().broadcast(
                    Text.literal(player.getName().getString() + " 님이 \"" + heard + "\" 에서 '" + bad
                            + "' 때문에 사망했습니다! (ㅇ 금지)").formatted(Formatting.RED),
                    false);
            player.kill();
        });
    }

    private static String parse(String json, String key) {
        try {
            return JsonParser.parseString(json).getAsJsonObject().get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] downsampleTo16k(short[] pcm) {
        int n = pcm.length / 3;
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int sum = pcm[i * 3] + pcm[i * 3 + 1] + pcm[i * 3 + 2];
            short v = (short) (sum / 3);
            out[i * 2] = (byte) (v & 0xFF);
            out[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return out;
    }

    private static Character findIeung(String text) {
        for (char c : text.toCharArray()) {
            if (c == '\u3147') return c;
            if (c >= 0xAC00 && c <= 0xD7A3) {
                int index = c - 0xAC00;
                int initial = index / 588;
                int fin = index % 28;
                if (initial == 11 || fin == 21) return c;
            }
        }
        return null;
    }
}
