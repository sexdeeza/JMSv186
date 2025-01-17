// Mob
package packet.content;

import client.MapleCharacter;
import client.MapleClient;
import client.status.MonsterStatus;
import client.status.MonsterStatusEffect;
import config.ServerConfig;
import debug.Debug;
import handling.MaplePacket;
import handling.channel.handler.MobHandler;
import handling.channel.handler.MovementParse;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import packet.ClientPacket;
import packet.ServerPacket;
import packet.Structure;
import server.Randomizer;
import server.life.MapleMonster;
import server.life.MobSkill;
import server.life.MobSkillFactory;
import server.maps.MapleMap;
import server.movement.AbsoluteLifeMovement;
import server.movement.AranMovement;
import server.movement.BounceMovement;
import server.movement.ChairMovement;
import server.movement.ChangeEquipSpecialAwesome;
import server.movement.JumpDownMovement;
import server.movement.LifeMovementFragment;
import server.movement.RelativeLifeMovement;
import tools.Pair;

public class MobPacket {

    public static boolean OnPacket(ClientPacket p, ClientPacket.Header header, MapleClient c) {
        MapleCharacter chr = c.getPlayer();
        if (chr == null) {
            return false;
        }

        MapleMap map = chr.getMap();
        if (map == null) {
            return false;
        }

        int oid = p.Decode4();

        MapleMonster monster = map.getMonsterByOid(oid);

        if (monster == null) {
            return false;
        }

        switch (header) {
            case CP_MobMove: {
                OnMove(p, chr, monster);
                return true;
            }
            case CP_MobApplyCtrl: {
                MobHandler.AutoAggro(chr, monster);
                return true;
            }
            case CP_MobDropPickUpRequest: {
                return true;
            }
            case CP_MobHitByObstacle: {
                return true;
            }
            case CP_MobHitByMob: {
                p.Decode4(); // skip
                int oid_to = p.Decode4();

                MapleMonster monster_to = map.getMonsterByOid(oid_to);

                if (monster_to == null) {
                    return false;
                }

                MobHandler.FriendlyDamage(chr, monster, monster_to);
                return true;
            }
            case CP_MobSelfDestruct: {
                MobHandler.MonsterBomb(chr, monster);
                return true;
            }
            case CP_MobAttackMob: {
                p.Decode4();
                int oid_to = p.Decode4();

                MapleMonster monster_to = map.getMonsterByOid(oid_to);

                if (monster_to == null) {
                    return false;
                }

                p.Decode1();

                int damage = p.Decode4();

                MobHandler.HypnotizeDmg(chr, monster, monster_to, damage);
                return true;
            }
            case CP_MobSkillDelayEnd: {
                return true;
            }
            case CP_MobTimeBombEnd: {
                return true;
            }
            case CP_MobEscortCollision: {
                int newNode = p.Decode4();
                MobHandler.MobNode(chr, monster, newNode);
                return true;
            }
            case CP_MobRequestEscortInfo: {
                MobHandler.DisplayNode(chr, monster);
                return true;
            }
            case CP_MobEscortStopEndRequest: {
                return true;
            }
            default: {
                break;
            }
        }

        return false;
    }

    // MoveMonster
    public static boolean OnMove(ClientPacket p, MapleCharacter chr, MapleMonster monster) {
        final short moveid = p.Decode2();
        final boolean useSkill = p.Decode1() > 0;

        MobUsesSkill(chr, monster, moveid, useSkill);

        final byte skill = p.Decode1();
        // 1st decode4
        final int skill1 = p.Decode1() & 0xFF;
        final int skill2 = p.Decode1();
        final int skill3 = p.Decode1();
        final int skill4 = p.Decode1();

        final Point startPos = monster.getPosition();

        if (ServerConfig.version <= 131) {
            p.Decode1();
            startPos.x = p.Decode2();
            startPos.y = p.Decode2();
        } else {
            // v186. Skipped bytes
            p.Decode4(); // 0
            p.Decode4(); // 0
            p.Decode1(); // 0
            p.Decode4(); // 1
            p.Decode4();
            p.Decode4();
            p.Decode4();
            p.Decode2(); // X
            p.Decode2(); // Y
            p.Decode2(); // 0
            p.Decode2(); // 0
        }
        /*
        if (monster.getId() == 8300006 || monster.getId() == 8300007) {
        }
         */
        List<LifeMovementFragment> res = null;
        try {
            res = parseMovement(p, 2);
        } catch (ArrayIndexOutOfBoundsException e) {
            Debug.ErrorLog("AIOBE Type2");
            return false;
        }

        if (res == null) {
            return false;
        }

        final MapleMap map = chr.getMap();
        MovementParse.updatePosition(res, monster, -1);
        map.moveMonster(monster, monster.getPosition());
        map.broadcastMessage(chr, MobPacket.moveMonster(useSkill, skill, skill1, skill2, skill3, skill4, monster.getObjectId(), startPos, monster.getPosition(), res), monster.getPosition());
        return true;
    }

    public static void MobUsesSkill(MapleCharacter chr, MapleMonster monster, short moveid, boolean useSkill) {
        int realskill = 0;
        int level = 0;

        if (useSkill) {// && (skill == -1 || skill == 0)) {
            final byte size = monster.getNoSkills();
            boolean used = false;

            if (size > 0) {
                final Pair<Integer, Integer> skillToUse = monster.getSkills().get((byte) Randomizer.nextInt(size));
                realskill = skillToUse.getLeft();
                level = skillToUse.getRight();
                // Skill ID and Level
                final MobSkill mobSkill = MobSkillFactory.getMobSkill(realskill, level);

                if (mobSkill != null && !mobSkill.checkCurrentBuff(chr, monster)) {
                    final long now = System.currentTimeMillis();
                    final long ls = monster.getLastSkillUsed(realskill);

                    if (ls == 0 || ((now - ls) > mobSkill.getCoolTime())) {
                        monster.setLastSkillUsed(realskill, now, mobSkill.getCoolTime());

                        final int reqHp = (int) (((float) monster.getHp() / monster.getMobMaxHp()) * 100); // In case this monster have 2.1b and above HP
                        if (reqHp <= mobSkill.getHP()) {
                            used = true;
                            mobSkill.applyEffect(chr, monster, true);
                        }
                    }
                }
            }
            if (!used) {
                realskill = 0;
                level = 0;
            }
        }

        chr.getClient().SendPacket(MobPacket.moveMonsterResponse(monster, moveid, realskill, level));
    }

    public static final List<LifeMovementFragment> parseMovement(ClientPacket p, int kind) {
        final List<LifeMovementFragment> res = new ArrayList<LifeMovementFragment>();
        final byte numCommands = p.Decode1();

        for (byte i = 0; i < numCommands; i++) {
            final byte command = p.Decode1();
            switch (command) {
                case -1: // Bounce movement?
                case 0x12: // Soaring?
                {
                    final short xpos = p.Decode2();
                    final short ypos = p.Decode2();
                    final short unk = p.Decode2();
                    final short fh = p.Decode2();
                    final byte newstate = p.Decode1();
                    final short duration = p.Decode2();
                    final BounceMovement bm = new BounceMovement(command, new Point(xpos, ypos), duration, newstate);
                    bm.setFH(fh);
                    bm.setUnk(unk);
                    res.add(bm);
                    break;
                }
                case 0:
                case 5:
                case 0xE:
                case 0x24:
                case 0x25: {
                    final short xpos = p.Decode2();
                    final short ypos = p.Decode2();
                    final short xwobble = p.Decode2();
                    final short ywobble = p.Decode2();
                    final short unk = p.Decode2();
                    short xoffset = 0;
                    short yoffset = 0;

                    if (ServerConfig.version > 131) {
                        xoffset = p.Decode2();
                        yoffset = p.Decode2();
                    }

                    final byte newstate = p.Decode1();
                    final short duration = p.Decode2();
                    final AbsoluteLifeMovement alm = new AbsoluteLifeMovement(command, new Point(xpos, ypos), duration, newstate);
                    alm.setUnk(unk);
                    alm.setPixelsPerSecond(new Point(xwobble, ywobble));

                    if (ServerConfig.version > 131) {
                        alm.setOffset(new Point(xoffset, yoffset));
                    }

                    res.add(alm);
                    break;
                }
                case 1:
                case 2:
                case 0xD:
                case 0x11:
                case 0x13:
                case 0x20:
                case 0x21:
                case 0x22:
                case 0x23: {
                    final short xmod = p.Decode2();
                    final short ymod = p.Decode2();
                    final byte newstate = p.Decode1();
                    final short duration = p.Decode2();
                    final RelativeLifeMovement rlm = new RelativeLifeMovement(command, new Point(xmod, ymod), duration, newstate);
                    res.add(rlm);
                    break;
                }
                case 0xF:
                case 0x10:
                case 0x14:
                case 0x15:
                case 0x16:
                case 0x17:
                case 0x18:
                case 0x19:
                case 0x1A:
                case 0x1B:
                case 0x1C:
                case 0x1D:
                case 0x1E:
                case 0x1F: {
                    final byte newstate = p.Decode1();
                    final short unk = p.Decode2();
                    final AranMovement am = new AranMovement(command, new Point(0, 0), unk, newstate);
                    res.add(am);
                    break;
                }
                case 3:
                case 4:
                case 6:
                case 7:
                case 8:
                case 0xA:
                case 0xB: {
                    final short xpos = p.Decode2();
                    final short ypos = p.Decode2();
                    final short unk = p.Decode2();
                    final byte newstate = p.Decode1();
                    final short duration = p.Decode2();
                    final ChairMovement cm = new ChairMovement(command, new Point(xpos, ypos), duration, newstate);
                    cm.setUnk(unk);
                    res.add(cm);
                    break;
                }
                case 9: {// change equip ???
                    res.add(new ChangeEquipSpecialAwesome(command, p.Decode1()));
                    break;
                }
                case 0xC: { // Jump Down
                    final short xpos = p.Decode2();
                    final short ypos = p.Decode2();
                    final short xwobble = p.Decode2();
                    final short ywobble = p.Decode2();
                    final short unk = p.Decode2();
                    final short fh = p.Decode2();
                    final short xoffset = p.Decode2();
                    final short yoffset = p.Decode2();
                    final byte newstate = p.Decode1();
                    final short duration = p.Decode2();
                    final JumpDownMovement jdm = new JumpDownMovement(command, new Point(xpos, ypos), duration, newstate);
                    jdm.setUnk(unk);
                    jdm.setPixelsPerSecond(new Point(xwobble, ywobble));
                    jdm.setOffset(new Point(xoffset, yoffset));
                    jdm.setFH(fh);
                    res.add(jdm);
                    break;
                }
                default:
                    final byte newstate = p.Decode1();
                    final short unk = p.Decode2();
                    final AranMovement am = new AranMovement(command, new Point(0, 0), unk, newstate);
                    res.add(am);
                    break;
            }
        }
        if (numCommands != res.size()) {
            System.out.println("error in movement");
            return null; // Probably hack
        }
        return res;
    }

    // spawnMonster
    public static MaplePacket Spawn(MapleMonster life, int spawnType, int effect, int link) {
        ServerPacket p = new ServerPacket(ServerPacket.Header.LP_MobEnterField);

        p.Encode4(life.getObjectId());
        p.Encode1(1); // 1 = Control normal, 5 = Control none
        p.Encode4(life.getId());
        if (ServerConfig.version <= 164) {
            p.Encode4(0); // 後でなおす
        } else {
            p.EncodeBuffer(Structure.MonsterStatus(life));
        }
        p.Encode2(life.getPosition().x);
        p.Encode2(life.getPosition().y);
        p.Encode1(life.getStance());
        p.Encode2(0); // FH
        p.Encode2(life.getFh()); // Origin FH

        if (effect != 0 || link != 0) {
            p.Encode1(effect != 0 ? effect : -3);
            p.Encode4(link);
        } else {
            if (spawnType == 0) {
                p.Encode4(effect);
            }
            p.Encode1(spawnType); // newSpawn ? -2 : -1
            //0xFB when wh spawns
        }
        p.Encode1(life.getCarnivalTeam());

        if (ServerConfig.version > 131) {
            p.Encode4(0);
        }

        if (ServerConfig.version > 164) {
            p.Encode4(0);
        }

        return p.Get();
    }

    // killMonster
    public static MaplePacket Kill(MapleMonster m, int animation) {
        ServerPacket p = new ServerPacket(ServerPacket.Header.LP_MobLeaveField);

        p.Encode4(m.getObjectId());
        p.Encode1(animation); // 0 = dissapear, 1 = fade out, 2+ = special
        return p.Get();
    }

    // ???
    public static MaplePacket Kill(int oid, int animation) {
        ServerPacket p = new ServerPacket(ServerPacket.Header.LP_MobLeaveField);

        p.Encode4(oid);
        p.Encode1(animation);
        return p.Get();
    }

    // controlMonster
    public static MaplePacket Control(MapleMonster life, boolean newSpawn, boolean aggro) {
        ServerPacket p = new ServerPacket(ServerPacket.Header.LP_MobChangeController);

        p.Encode1(aggro ? 2 : 1);
        p.Encode4(life.getObjectId());
        p.Encode1(1); // 1 = Control normal, 5 = Control none
        p.Encode4(life.getId());

        if (ServerConfig.version <= 164) {
            p.Encode4(0); // 後でなおす
        } else {
            p.EncodeBuffer(Structure.MonsterStatus(life));
        }

        p.Encode2(life.getPosition().x);
        p.Encode2(life.getPosition().y);
        p.Encode1(life.getStance()); // Bitfield
        p.Encode2(0); // FH
        p.Encode2(life.getFh()); // Origin FH
        p.Encode1(life.isFake() ? -4 : newSpawn ? -2 : -1);
        p.Encode1(life.getCarnivalTeam());

        if (ServerConfig.version > 131) {
            p.Encode4(0);
            p.Encode4(0);
        }

        return p.Get();
    }

    // stopControllingMonster
    public static MaplePacket StopControl(MapleMonster m) {
        ServerPacket p = new ServerPacket(ServerPacket.Header.LP_MobChangeController);

        p.Encode1(0);
        p.Encode4(m.getObjectId());
        return p.Get();
    }

    // showMonsterHP
    public static MaplePacket ShowHP(MapleMonster m, int remhppercentage) {
        ServerPacket p = new ServerPacket(ServerPacket.Header.LP_MobHPIndicator);

        p.Encode4(m.getObjectId());
        p.Encode1(remhppercentage);

        return p.Get();
    }

    // showBossHP
    public static MaplePacket ShowBossHP(MapleMonster m) {
        ServerPacket p = new ServerPacket(ServerPacket.Header.LP_FieldEffect);

        p.Encode1(5);
        p.Encode4(m.getId());

        if (m.getHp() > Integer.MAX_VALUE) {
            p.Encode4((int) (((double) m.getHp() / m.getMobMaxHp()) * Integer.MAX_VALUE));
        } else {
            p.Encode4((int) m.getHp());
        }

        if (m.getMobMaxHp() > Integer.MAX_VALUE) {
            p.Encode4(Integer.MAX_VALUE);
        } else {
            p.Encode4((int) m.getMobMaxHp());
        }

        p.Encode1(m.getStats().getTagColor());
        p.Encode1(m.getStats().getTagBgColor());
        return p.Get();
    }

    // damageMonster
    public static MaplePacket Damage(MapleMonster m, final long damage) {
        ServerPacket p = new ServerPacket(ServerPacket.Header.LP_MobDamaged);

        p.Encode4(m.getObjectId());
        p.Encode1(0);

        if (damage > Integer.MAX_VALUE) {
            p.Encode4(Integer.MAX_VALUE);
        } else {
            p.Encode4((int) damage);
        }

        return p.Get();
    }

    // ???
    public static MaplePacket Damage(int oid, final long damage) {
        ServerPacket p = new ServerPacket(ServerPacket.Header.LP_MobDamaged);

        p.Encode4(oid);
        p.Encode1(0);

        if (damage > Integer.MAX_VALUE) {
            p.Encode4(Integer.MAX_VALUE);
        } else {
            p.Encode4((int) damage);
        }

        return p.Get();
    }

    // healMonster
    public static MaplePacket Heal(MapleMonster m, final int heal) {
        ServerPacket p = new ServerPacket(ServerPacket.Header.LP_MobDamaged);

        p.Encode4(m.getObjectId());
        p.Encode1(0);
        p.Encode4(-heal);
        return p.Get();
    }

    public static MaplePacket applyMonsterStatus(final int oid, final MonsterStatus mse, int x, MobSkill skil) {
        ServerPacket p = new ServerPacket(ServerPacket.Header.LP_MobStatSet);

        p.Encode4(oid);
        p.Encode8(Structure.getSpecialLongMask(Collections.singletonList(mse)));
        p.Encode8(Structure.getLongMask(Collections.singletonList(mse)));

        p.Encode2(x);
        p.Encode2(skil.getSkillId());
        p.Encode2(skil.getSkillLevel());
        p.Encode2(mse.isEmpty() ? 1 : 0); // might actually be the buffTime but it's not displayed anywhere
        p.Encode2(0); // delay in ms
        p.Encode1(1); // size
        p.Encode1(1); // ? v97

        return p.Get();
    }

    public static MaplePacket applyMonsterStatus(final int oid, final MonsterStatusEffect mse) {
        ServerPacket p = new ServerPacket(ServerPacket.Header.LP_MobStatSet);

        p.Encode4(oid);
        //aftershock extra int here
        p.Encode8(Structure.getSpecialLongMask(Collections.singletonList(mse.getStati())));
        p.Encode8(Structure.getLongMask(Collections.singletonList(mse.getStati())));

        p.Encode2(mse.getX());
        if (mse.isMonsterSkill()) {
            p.Encode2(mse.getMobSkill().getSkillId());
            p.Encode2(mse.getMobSkill().getSkillLevel());
        } else if (mse.getSkill() > 0) {
            p.Encode4(mse.getSkill());
        }
        p.Encode2(mse.getStati().isEmpty() ? 1 : 0); // might actually be the buffTime but it's not displayed anywhere
        p.Encode2(0); // delay in ms
        p.Encode1(1); // size
        p.Encode1(1); // ? v97

        return p.Get();
    }

    public static MaplePacket applyMonsterStatus(final int oid, final Map<MonsterStatus, Integer> stati, final List<Integer> reflection, MobSkill skil) {

        ServerPacket p = new ServerPacket(ServerPacket.Header.LP_MobStatSet);

        p.Encode4(oid);
        p.Encode8(Structure.getSpecialLongMask(stati.keySet()));
        p.Encode8(Structure.getLongMask(stati.keySet()));

        for (Map.Entry<MonsterStatus, Integer> mse : stati.entrySet()) {
            p.Encode2(mse.getValue());
            p.Encode2(skil.getSkillId());
            p.Encode2(skil.getSkillLevel());
            p.Encode2(mse.getKey().isEmpty() ? 1 : 0); // might actually be the buffTime but it's not displayed anywhere
        }
        for (Integer ref : reflection) {
            p.Encode4(ref);
        }
        p.Encode4(0);
        p.Encode2(0); // delay in ms

        int size = stati.size(); // size
        if (reflection.size() > 0) {
            size /= 2; // This gives 2 buffs per reflection but it's really one buff
        }
        p.Encode1(size); // size
        p.Encode1(1); // ? v97

        return p.Get();
    }

    public static MaplePacket cancelMonsterStatus(int oid, MonsterStatus stat) {
        ServerPacket p = new ServerPacket(ServerPacket.Header.LP_MobStatReset);

        p.Encode4(oid);
        p.Encode8(Structure.getSpecialLongMask(Collections.singletonList(stat)));
        p.Encode8(Structure.getLongMask(Collections.singletonList(stat)));
        p.Encode1(1); // reflector is 3~!??
        p.Encode1(2); // ? v97

        return p.Get();
    }

    public static MaplePacket talkMonster(int oid, int itemId, String msg) {
        ServerPacket p = new ServerPacket(ServerPacket.Header.TALK_MONSTER);

        p.Encode4(oid);
        p.Encode4(500); //?
        p.Encode4(itemId);
        p.Encode1(itemId <= 0 ? 0 : 1);
        p.Encode1(msg == null || msg.length() <= 0 ? 0 : 1);

        if (msg != null && msg.length() > 0) {
            p.EncodeStr(msg);
        }
        p.Encode4(1); //?

        return p.Get();
    }

    public static MaplePacket removeTalkMonster(int oid) {
        ServerPacket p = new ServerPacket(ServerPacket.Header.REMOVE_TALK_MONSTER);

        p.Encode4(oid);
        return p.Get();
    }

    // damageFriendlyMob
    public static MaplePacket damageFriendlyMob(MapleMonster mob, final long damage, final boolean display) {
        ServerPacket p = new ServerPacket(ServerPacket.Header.LP_MobDamaged);

        p.Encode4(mob.getObjectId());
        p.Encode1(display ? 1 : 2); //false for when shammos changes map!

        if (damage > Integer.MAX_VALUE) {
            p.Encode4(Integer.MAX_VALUE);
        } else {
            p.Encode4((int) damage);
        }
        if (mob.getHp() > Integer.MAX_VALUE) {
            p.Encode4((int) (((double) mob.getHp() / mob.getMobMaxHp()) * Integer.MAX_VALUE));
        } else {
            p.Encode4((int) mob.getHp());
        }
        if (mob.getMobMaxHp() > Integer.MAX_VALUE) {
            p.Encode4(Integer.MAX_VALUE);
        } else {
            p.Encode4((int) mob.getMobMaxHp());
        }

        return p.Get();
    }

    // moveMonster
    public static MaplePacket moveMonster(boolean useskill, int skill, int skill1, int skill2, int skill3, int skill4, int oid, Point startPos, Point endPos, List<LifeMovementFragment> moves) {

        ServerPacket p = new ServerPacket(ServerPacket.Header.LP_MobMove);

        p.Encode4(oid);

        if (ServerConfig.version > 131) {
            p.Encode2(0); //moveid but always 0
        }

        p.Encode1(useskill ? 1 : 0); //?? I THINK
        p.Encode1(skill); // mode
        p.Encode1(skill1); // skillId
        p.Encode1(skill2); // skillLevel
        p.Encode1(skill3); // effectDelay
        p.Encode1(skill4); // effectDelay

        if (ServerConfig.version > 131) {
            p.EncodeZeroBytes(8); //o.o?
        }

        p.Encode2(startPos.x);
        p.Encode2(startPos.y);

        if (ServerConfig.version > 131) {
            p.Encode2(8); //? sometimes 0? sometimes 22? sometimes random numbers?
            p.Encode2(1);
        }

        p.EncodeBuffer(serializeMovementList(moves));

        return p.Get();
    }

    public static byte[] serializeMovementList(List<LifeMovementFragment> moves) {
        ServerPacket data = new ServerPacket();
        data.Encode1(moves.size());

        for (LifeMovementFragment move : moves) {
            move.serialize(data);
        }

        return data.Get().getBytes();
    }

    // moveMonsterResponse
    public static MaplePacket moveMonsterResponse(MapleMonster m, short moveid, int skillId, int skillLevel) {
        ServerPacket p = new ServerPacket(ServerPacket.Header.LP_MobCtrlAck);

        p.Encode4(m.getObjectId());
        p.Encode2(moveid);
        p.Encode1(m.isControllerHasAggro() ? 1 : 0);
        p.Encode2(m.getMp());
        p.Encode1(skillId);
        p.Encode1(skillLevel);

        return p.Get();
    }
}
