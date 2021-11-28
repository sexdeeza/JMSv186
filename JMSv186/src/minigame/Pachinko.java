package minigame;

import client.MapleCharacter;
import handling.MaplePacket;
import packet.InPacket;

public class Pachinko {

    // パチンコ情報の更新
    public static MaplePacket UpdateTama(MapleCharacter chr) {
        InPacket p = new InPacket(InPacket.Header.MINIGAME_PACHINKO_UPDATE_TAMA);
        // クライアント上ではDecodeBufferで12バイト分Decodeされる
        // キャラクターID (実質不要)
        p.Encode4(chr.getId());
        // アイテム欄の玉の数に反映される値
        p.Encode4(chr.getTama());
        // 用途不明
        p.Encode4(0);
        return p.Get();
    }

    // ポイントアイテムのパチンコ玉の充填 (玉ボックス)
    public static MaplePacket TamaBoxSuccess(int gain) {
        InPacket p = new InPacket(InPacket.Header.TAMA_BOX_SUCCESS);
        // パチンコ玉の数
        p.Encode4(gain);
        return p.Get();
    }

    // パチンコ玉の充填に失敗した場合のダイアログ (実質不要)
    public static MaplePacket TamaBoxFailure() {
        InPacket p = new InPacket(InPacket.Header.TAMA_BOX_FAILURE);
        return p.Get();
    }

    // チャット欄にパチンコ玉獲得のメッセージを表示
    public static final MaplePacket GainTamaMessage(int gain) {
        InPacket p = new InPacket(InPacket.Header.SHOW_STATUS_INFO);
        // パチンコ玉のフラグ
        p.Encode1((byte) 15);
        // パチンコ玉の数
        p.Encode4(gain);
        return p.Get();
    }
}
