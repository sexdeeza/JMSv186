/*
This file is part of the OdinMS Maple Story Server
Copyright (C) 2008 ~ 2010 Patrick Huy <patrick.huy@frz.cc> 
Matthias Butz <matze@odinms.de>
Jan Christian Meyer <vimes@odinms.de>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License version 3
as published by the Free Software Foundation. You may not use, modify
or distribute this program under any other version of the
GNU Affero General Public License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package handling.login.handler;

import java.util.List;
import java.util.Calendar;
import client.inventory.IItem;
import client.inventory.Item;
import client.MapleClient;
import client.MapleCharacter;
import client.MapleCharacterUtil;
import client.inventory.MapleInventory;
import client.inventory.MapleInventoryType;
import config.DebugConfig;
import config.ServerConfig;
import debug.Debug;
import handling.login.LoginInformationProvider;
import handling.login.LoginServer;
import handling.login.LoginWorker;
import packet.ClientPacket;
import server.MapleItemInformationProvider;
import server.quest.MapleQuest;
import tools.MaplePacketCreator;
import tools.packet.LoginPacket;
import tools.KoreanDateUtil;

public class CharLoginHandler {

    private static int SelectedWorld = 0;
    private static int SelectedChannel = 0;

    private static final boolean loginFailCount(final MapleClient c) {
        c.loginAttempt++;
        if (c.loginAttempt > 5) {
            return true;
        }
        return false;
    }

    public static final boolean login(ClientPacket p, final MapleClient c) {
        String login = new String(p.DecodeBuffer());
        final String pwd = new String(p.DecodeBuffer());
        boolean endwith_ = false;

        // MapleIDは最低4文字なので、5文字以上の場合に性別変更の特殊判定を行う
        if (login.length() >= 5 && login.endsWith("_")) {
            login = login.substring(0, login.length() - 1);
            endwith_ = true;

            Debug.InfoLog("[FEMALE MODE] \"" + login + "\"");
        }

        c.setAccountName(login);
        final boolean ipBan = c.hasBannedIP();
        final boolean macBan = c.hasBannedMac();

        int loginok = c.login(login, pwd, ipBan || macBan);

        if (loginok == 5) {
            if (c.auto_register(login, pwd) == 1) {
                Debug.InfoLog("[NEW MAPLEID] \"" + login + "\"");
                loginok = c.login(login, pwd, ipBan || macBan);
            }
        }

        final Calendar tempbannedTill = c.getTempBanCalendar();

        if (loginok == 0 && (ipBan || macBan) && !c.isGm()) {
            loginok = 3;
            if (macBan) {
                // this is only an ipban o.O" - maybe we should refactor this a bit so it's more readable
                MapleCharacter.ban(c.getSession().getRemoteAddress().toString().split(":")[0], "Enforcing account ban, account " + login, false, 4, false);
            }
        }
        if (loginok != 0) {
            if (!loginFailCount(c)) {
                c.getSession().write(LoginPacket.getLoginFailed(loginok));
            }
        } else if (tempbannedTill.getTimeInMillis() != 0) {
            if (!loginFailCount(c)) {
                c.getSession().write(LoginPacket.getTempBan(KoreanDateUtil.getTempBanTimestamp(tempbannedTill.getTimeInMillis()), c.getBanReason()));
            }
        } else {
            c.loginAttempt = 0;
            // アカウントの性別変更
            if (endwith_) {
                c.setGender((byte) 1);
            }
            LoginWorker.registerClient(c);
            return true;
        }
        return false;
    }

    public static final void ServerListRequest(final MapleClient c) {
        // かえで
        c.getSession().write(LoginPacket.getServerList(0));
        // もみじ (サーバーを分離すると接続人数を取得するのが難しくなる)
        c.getSession().write(LoginPacket.getServerList(1, false, 16));
        c.getSession().write(LoginPacket.getEndOfServerList());
    }

    // 必要なさそう
    public static final void ServerStatusRequest(final MapleClient c) {
        // 0 = Select world normally
        // 1 = "Since there are many users, you may encounter some..."
        // 2 = "The concurrent users in this world have reached the max"
        final int numPlayer = LoginServer.getUsersOn();
        final int userLimit = LoginServer.getUserLimit();
        if (numPlayer >= userLimit) {
            c.getSession().write(LoginPacket.getServerStatus(2));
        } else if (numPlayer * 2 >= userLimit) {
            c.getSession().write(LoginPacket.getServerStatus(1));
        } else {
            c.getSession().write(LoginPacket.getServerStatus(0));
        }
    }

    public static final void CharlistRequest(ClientPacket p, final MapleClient c) {
        int server = p.Decode1();
        final int channel = p.Decode1() + 1;

        if (server == 12) {
            server = 0;
        }

        SelectedWorld = server;
        SelectedChannel = channel - 1;

        c.setWorld(server);
        //System.out.println("Client " + c.getSession().getRemoteAddress().toString().split(":")[0] + " is connecting to server " + server + " channel " + channel + "");
        c.setChannel(channel);

        final List<MapleCharacter> chars = c.loadCharacters(server);
        if (chars != null) {
            c.getSession().write(LoginPacket.getCharList(c.getSecondPassword() != null, chars, c.getCharacterSlots()));
        } else {
            c.getSession().close();
        }
    }

    public static final void CheckCharName(ClientPacket p, final MapleClient c) {
        String name = new String(p.DecodeBuffer());
        c.getSession().write(LoginPacket.charNameResponse(name,
                !MapleCharacterUtil.canCreateChar(name) || LoginInformationProvider.getInstance().isForbiddenName(name)));
    }

    public static final void CreateChar(ClientPacket p, final MapleClient c) {
        final String name = new String(p.DecodeBuffer());

        if (ServerConfig.version <= 164) {
            final int face = p.Decode4();
            final int hair = p.Decode4();
            final int hairColor = 0;
            final byte skinColor = (byte) 0;
            final int top = p.Decode4();
            final int bottom = p.Decode4();
            final int shoes = p.Decode4();
            final int weapon = p.Decode4();
            final byte gender = c.getGender();

            MapleCharacter newchar = MapleCharacter.getDefault(c, 1);

            // サイコロ
            if (ServerConfig.version <= 131) {
                newchar.getStat().str = p.Decode1();
                newchar.getStat().dex = p.Decode1();
                newchar.getStat().int_ = p.Decode1();
                newchar.getStat().luk = p.Decode1();
            }

            newchar.setWorld((byte) c.getWorld());
            newchar.setFace(face);
            newchar.setHair(hair + hairColor);
            newchar.setGender(gender);
            newchar.setName(name);
            newchar.setSkinColor(skinColor);

            MapleInventory equip = newchar.getInventory(MapleInventoryType.EQUIPPED);
            final MapleItemInformationProvider li = MapleItemInformationProvider.getInstance();

            IItem item = li.getEquipById(top);
            item.setPosition((byte) -5);
            equip.addFromDB(item);

            item = li.getEquipById(bottom);
            item.setPosition((byte) -6);
            equip.addFromDB(item);

            item = li.getEquipById(shoes);
            item.setPosition((byte) -7);
            equip.addFromDB(item);

            item = li.getEquipById(weapon);
            item.setPosition((byte) -11);
            equip.addFromDB(item);

            if (MapleCharacterUtil.canCreateChar(name) && !LoginInformationProvider.getInstance().isForbiddenName(name)) {
                AddStarterSet(newchar);
                MapleCharacter.saveNewCharToDB(newchar, 1, false);
                c.getSession().write(LoginPacket.addNewCharEntry(newchar, true));
                c.createdChar(newchar.getId());
            } else {
                c.getSession().write(LoginPacket.addNewCharEntry(newchar, false));
            }
            return;
        }
        final int JobType = p.Decode4();

        short db = 0;

        if (ServerConfig.version > 176) {
            db = p.Decode2();
        }

        final int face = p.Decode4();
        final int hair = p.Decode4();
        final int hairColor = 0;
        final byte skinColor = (byte) 0;
        final int top = p.Decode4();
        final int bottom = p.Decode4();
        final int shoes = p.Decode4();
        final int weapon = p.Decode4();
        final byte gender = c.getGender();

        MapleCharacter newchar = MapleCharacter.getDefault(c, JobType);
        newchar.setWorld((byte) c.getWorld());
        newchar.setFace(face);
        newchar.setHair(hair + hairColor);
        newchar.setGender(gender);
        newchar.setName(name);
        newchar.setSkinColor(skinColor);

        MapleInventory equip = newchar.getInventory(MapleInventoryType.EQUIPPED);
        final MapleItemInformationProvider li = MapleItemInformationProvider.getInstance();

        IItem item = li.getEquipById(top);
        item.setPosition((byte) -5);
        equip.addFromDB(item);

        if (db == 0) {
            item = li.getEquipById(bottom);
            item.setPosition((byte) -6);
            equip.addFromDB(item);
        }

        item = li.getEquipById(shoes);
        item.setPosition((byte) -7);
        equip.addFromDB(item);

        item = li.getEquipById(weapon);
        item.setPosition((byte) -11);
        equip.addFromDB(item);

        //blue/red pots
        if (JobType == 0) {
            newchar.setQuestAdd(MapleQuest.getInstance(20022), (byte) 1, "1");
            newchar.setQuestAdd(MapleQuest.getInstance(20010), (byte) 1, null); //>_>_>_> ugh
            newchar.setQuestAdd(MapleQuest.getInstance(20000), (byte) 1, null); //>_>_>_> ugh
            newchar.setQuestAdd(MapleQuest.getInstance(20015), (byte) 1, null); //>_>_>_> ugh
            newchar.setQuestAdd(MapleQuest.getInstance(20020), (byte) 1, null); //>_>_>_> ugh
        }

        if (MapleCharacterUtil.canCreateChar(name) && !LoginInformationProvider.getInstance().isForbiddenName(name)) {
            AddStarterSet(newchar);
            MapleCharacter.saveNewCharToDB(newchar, JobType, JobType == 1 && db > 0);

            c.getSession().write(LoginPacket.addNewCharEntry(newchar, true));
            c.createdChar(newchar.getId());
        } else {
            c.getSession().write(LoginPacket.addNewCharEntry(newchar, false));
        }
    }

    public static final void DeleteChar(ClientPacket p, final MapleClient c) {
        byte state = 0;
        // BB後
        if (ServerConfig.version >= 188) {
            String MapleID = p.DecodeStr();
            if (!MapleID.equals(c.getAccountName())) {
                // state = 0以外にすると切断されます
            }
        }
        final int Character_ID = p.Decode4();

        if (!c.login_Auth(Character_ID)) {
            c.getSession().close();
            return;
        }

        if (state == 0) {
            state = (byte) c.deleteCharacter(Character_ID);
        }

        c.getSession().write(LoginPacket.deleteCharResponse(Character_ID, state));
    }

    public static final boolean Character_WithSecondPassword(ClientPacket p, final MapleClient c) {
        final int charId = p.Decode4();

        if (loginFailCount(c) || !c.login_Auth(charId)) { // This should not happen unless player is hacking
            c.getSession().close();
            return false;
        }
        if (c.getIdleTask() != null) {
            c.getIdleTask().cancel(true);
        }
        c.updateLoginState(MapleClient.LOGIN_SERVER_TRANSITION, c.getSessionIPAddress());
        //c.getSession().write(MaplePacketCreator.getServerIP(Integer.parseInt(ChannelServer.getInstance(c.getChannel()).getIP().split(":")[1]), charId));
        c.getSession().write(MaplePacketCreator.getServerIP(LoginServer.WorldPort[SelectedWorld] + SelectedChannel, charId));
        return true;
    }

    // 初期アイテムをキャラクターデータに追加
    public static boolean AddItem(MapleCharacter chr, int itemid) {
        return AddItem(chr, itemid, 1);
    }

    public static boolean AddItem(MapleCharacter chr, int itemid, int count) {
        MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        // 存在しないitemid
        if (!ii.itemExists(itemid)) {
            Debug.ErrorLog("AddItem: " + itemid);
            return false;
        }

        switch (itemid / 1000000) {
            case 1: {
                MapleInventory equip = chr.getInventory(MapleInventoryType.EQUIP);
                equip.addItem(ii.getEquipById(itemid));
                break;
            }
            case 2: {
                MapleInventory use = chr.getInventory(MapleInventoryType.USE);
                use.addItem(new Item(itemid, (byte) 0, (short) count, (byte) 0));
                break;
            }
            case 3: {
                MapleInventory setup = chr.getInventory(MapleInventoryType.SETUP);
                setup.addItem(new Item(itemid, (byte) 0, (short) 1, (byte) 0));
                break;
            }
            case 4: {
                MapleInventory etc = chr.getInventory(MapleInventoryType.ETC);
                etc.addItem(new Item(itemid, (byte) 0, (short) count, (byte) 0));
                break;
            }
            case 5: {
                MapleInventory cash = chr.getInventory(MapleInventoryType.CASH);
                cash.addItem(new Item(itemid, (byte) 0, (short) count, (byte) 0));
                break;
            }
            default: {
                return false;
            }
        }

        return true;
    }

    public static boolean AddStarterSet(MapleCharacter chr) {
        if (!DebugConfig.starter_set) {
            return false;
        }
        // メル
        chr.setMeso(777000000);
        // パチンコ玉
        chr.setTama(500000);

        // エリクサー
        AddItem(chr, 2000004, 100);
        // 万病治療薬
        AddItem(chr, 2050004, 100);
        // コーヒー牛乳
        AddItem(chr, 2030008, 100);
        // いちご牛乳
        AddItem(chr, 2030009, 100);
        // フルーツ牛乳
        AddItem(chr, 2030010, 100);
        // 帰還の書(ヘネシス)
        AddItem(chr, 2030004, 100);
        // 強化書
        AddItem(chr, 2040303, 100);
        AddItem(chr, 2040506, 100);
        AddItem(chr, 2040710, 100);
        AddItem(chr, 2040807, 100);
        AddItem(chr, 2044703, 100);
        AddItem(chr, 2044503, 100);
        AddItem(chr, 2043803, 100);
        AddItem(chr, 2043003, 100);
        AddItem(chr, 2049100, 100);
        AddItem(chr, 2049003, 100);

        if (ServerConfig.version >= 186) {
            AddItem(chr, 2049300, 100);
            AddItem(chr, 2049400, 100);
            AddItem(chr, 2470000, 100);
        }

        // 魔法の石
        AddItem(chr, 4006000, 100);
        // 召喚の石
        AddItem(chr, 4006001, 100);

        // 軍手(茶)
        AddItem(chr, 1082149);
        // ドロシー(銀)
        AddItem(chr, 1072264);
        // 冒険家のマント(黄)
        AddItem(chr, 1102040);
        // 緑ずきん
        AddItem(chr, 1002391);
        // オウルアイ
        AddItem(chr, 1022047);
        // 犬鼻
        AddItem(chr, 1012056);
        // メイプルシールド
        AddItem(chr, 1092030);
        // タオル(黒)
        AddItem(chr, 1050127);
        // バスタオル(黄)
        AddItem(chr, 1051140);
        // エレメントピアス
        AddItem(chr, 1032062);
        if (ServerConfig.version >= 186) {
            // 錬金術師の指輪
            AddItem(chr, 1112400);
        }

        // ドラゴン(アビス)
        AddItem(chr, 3010047);

        // 拡声器
        AddItem(chr, 3010047, 100);
        // アイテム拡声器
        AddItem(chr, 5076000, 100);
        // 黒板
        AddItem(chr, 5370000, 100);
        // 営業許可証
        AddItem(chr, 5140000, 100);
        // 高性能テレポストーン
        AddItem(chr, 5041000, 100);
        // ガシャポンチケット
        AddItem(chr, 5220000, 100);
        if (ServerConfig.version >= 186) {
            // ビシャスのハンマー
            AddItem(chr, 5570000, 100);
            // ミラクルキューブ
            AddItem(chr, 5062000, 100);
            // ベガの呪文書(10%)
            AddItem(chr, 5610000, 100);
            // ベガの呪文書(60%)
            AddItem(chr, 5610001, 100);
        }
        // AP再分配の書
        AddItem(chr, 5050000, 100);

        return true;
    }
}
