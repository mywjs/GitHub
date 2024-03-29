package com.game.businessSystem.gameCopies.instances;

import java.util.ArrayList;
import java.util.List;

import org.apache.mina.core.buffer.IoBuffer;

import com.alibaba.fastjson.annotation.JSONField;
import com.game.businessSystem.achievement.define.AchievementTypeDefine;
import com.game.businessSystem.achievement.reference.AchievementReferenceManager;
import com.game.businessSystem.combat.CombatIndex;
import com.game.businessSystem.combat.CombatModule;
import com.game.businessSystem.combat.CombatPartner;
import com.game.businessSystem.combat.event.CombatEvent;
import com.game.businessSystem.combat.impl.CombatModuleGeneral;
import com.game.businessSystem.dropControl.DropControlReferenceManager;
import com.game.businessSystem.dropControl.DropResult;
import com.game.businessSystem.friend.FriendManager;
import com.game.businessSystem.gameActivity.activity.ActivityRecord;
import com.game.businessSystem.gameActivity.event.EnterEvent;
import com.game.businessSystem.gameActivity.event.GeneralResponseEvent;
import com.game.businessSystem.gameCopies.GameCopyRecordCollection;
import com.game.businessSystem.gameCopies.define.DefineCopyEvent;
import com.game.businessSystem.gameCopies.reference.CopyReference;
import com.game.businessSystem.gameCopies.reference.MountainReferenceManager;
import com.game.event.Event;
import com.game.log.eventlog.EventLog;
import com.game.network.impl.IoBufferUtil;
import com.game.partner.instance.PartnerManager;
import com.game.partner.instance.impl.Partner;
import com.game.partner.reference.PartnerReferenceManager;
import com.game.player.Player;
import com.game.util.NumberDefine;
import com.game.util.Response;
import com.game.util.TipsString;
import com.game.util.Utils;

/**
 * 类说明 :
 * 
 * @author Jimsun
 * @version V1.0 2013-12-17 下午2:57:19
 */
public class GameCopyRecord extends ActivityRecord<CopyReference, CombatModuleGeneral> {
	// 进度,即节点值
	private long lastEnterTime;// 单位毫秒
	private byte mark;
	// 进度序号
	private int progress = 0;
	private boolean isLocked;
	private long pushId;
	private byte enterTimes;
	private byte remainMP = -1;
	private byte skillRound[];

	private transient DropResult dropResult;
	private boolean firstPass = true;
	private byte buyTimes;

	// private float x;
	// private float y;
	public GameCopyRecord() {

	}

	public GameCopyRecord(int copyId) {

	}

	public GameCopyRecord(CopyReference copyReference) {
		this.id = copyReference.getRefId();
		this.isLocked = copyReference.isLocked();
	}

	public int[] nextProgressInit(Player player,boolean isReInit) {
		Partner[] playerPartners = new Partner[6];
		System.arraycopy(player.getTeamCollection().getPartners(), 0, playerPartners, 0, 5);

		if(pushId>0){
			playerPartners[5] = player.getFriendProperty().getRecommendRobotParter(pushId);
		}

		List<Integer> idList = getReference().getMonsterIdList().get(getProgress());
		Partner[] enemyPartners = new Partner[idList.size()];
		for (int i = 0; i < enemyPartners.length; i++) {
			enemyPartners[i] = PartnerManager.createPartner(idList.get(i), true);
			player.log("战斗初始化怪物ID:" + enemyPartners[i].getId());
		}
		
		if(isReInit){
			getCombatModule().reInit(playerPartners, enemyPartners);
			
			String myPartnerHp = "我方卡牌总血量:"+getCombatModule().getMyMaxHp();
			for(int i=0;i<playerPartners.length;i++){
				if(playerPartners[i]!=null){
					myPartnerHp = myPartnerHp + "[id:" +playerPartners[i].getId()+" refId:"+playerPartners[i].getRefenceId()+" hp:"+playerPartners[i].getFightProperty().getHpValue()+"]      ";
				}
			}
			player.log(myPartnerHp);
			return null;
		}else{
			int[] seeds = getCombatModule().init(player, playerPartners, enemyPartners, getReference().getMp(), getReference().getMpMax(), getReference().getMpSubstract(), getReference().getMpRecoverySkill(), getSceneId());
			
			String myPartnerHp = "我方卡牌总血量:"+getCombatModule().getMyMaxHp();
			for(int i=0;i<playerPartners.length;i++){
				if(playerPartners[i]!=null){
					myPartnerHp = myPartnerHp + "[id:" +playerPartners[i].getId()+" refId:"+playerPartners[i].getRefenceId()+" hp:"+playerPartners[i].getFightProperty().getHpValue()+"]      ";
				}
			}
			player.log(myPartnerHp);
			return seeds;
		}
	}

	@Override
	public void quit(Event<Player> event) {
		Player player  = event.getAccountBase().getCurrentRole();
		if(dropResult!=null){
			dropResult.clear();
		}
		resetProgress();
		player.log("退出副本:" + this);
		player.setCombatScene(null);
	}

	@Override
	public IoBuffer packInfo(IoBuffer ioBuffer, Player player) {
		checkReset(player);
		ioBuffer.putInt(getId());
		ioBuffer.put(getMark());
		// 每天进入次数
		ioBuffer.put((byte) (enterTimes));
		ioBuffer.put(buyTimes);
		return ioBuffer;
	}

	@Override
	public short enterLogic(EnterEvent event) {

		Player player = event.getAccountBase().getCurrentRole();
		player.log("进入" + this);

		checkReset(player);

		IoBuffer ioBuffer = Utils.getIoBuffer();
		if (dropResult == null) {
			dropResult = new DropResult();
		}
		dropResult.toIoBuffer(ioBuffer);
		int[] seeds = nextProgressInit(player,false);
		
		ioBuffer.put((byte) getProgress());
		for (int s : seeds) {
			ioBuffer.putInt(s);
		}
		if(remainMP==-1){
			ioBuffer.put((byte) getReference().getMp());
		}else{
			ioBuffer.put(remainMP);
		}
		
		CombatPartner[] ps = getCombatModule().getCamp()[CombatModule.CAMP_ME].getCombatPartner();
		if (skillRound == null) {
			skillRound = new byte[ps.length];
		}
		ioBuffer.put((byte) ps.length);
		for(byte i=0;i<ps.length;i++){
			ioBuffer.put(i);
			ioBuffer.put(skillRound[i]);
		}
		
		GeneralResponseEvent.send(event, ioBuffer, DefineCopyEvent.ENTER_RESPONSE_EVENT);
		player.getGameCopyRecordCollection().setLastCopyId((short) getId());

		this.lastEnterTime = System.currentTimeMillis();
		return Response.STATE_SUCCESS;
	}

	@Override
	public short canEnter(Player player) {
		checkReset(player);
		player.log("进入副本条件判断：玩家体力" + player.getBaseProperty().getPower() + " 进入次数:" + enterTimes);
		if (player.getBaseProperty().getPower() < getReference().getPower()) {
			return TipsString.POWER_NOT_ENOUGH;
		}
		if (enterTimes >= getReference().getMaxEnterTimes()) {
			return TipsString.COPY_ENTER_NOT_ENOUGH;
		}
		if (player.getPartnerBag().isFull()) {
			return TipsString.PARTNERBAG_FULL;
		}
		if (player.getLevel() < MountainReferenceManager.getMountainsReference(getReference().getMountainId()).getLevelLimit()) {
			return TipsString.COPY_LEVEL_LIMIT;
		}
		return Response.STATE_SUCCESS;
	}

	@Override
	public void reset(Player player) {
		player.log("普通副本重置");
		enterTimes = 0;
		buyTimes = 0;
		remainMP = -1;
		player.getGameCopyRecordCollection().getData().getAddFriendInfo().clear();
	}

	@Override
	public void killBossResult(CombatEvent event) {
		Player player = event.getAccountBase().getCurrentRole();
		GameCopyRecordCollection gameCopyRecordCollection = player.getGameCopyRecordCollection();
		GameCopyRecord gameCopyRecord = player.getLastGameCopyRecord();
		CopyReference copyReference = MountainReferenceManager.getCopyReference(gameCopyRecord.getId());

		DropResult dropResult = gameCopyRecord.getDropResult();
		player.log("副本杀死boss逻辑");
		IoBuffer ioBuffer = null;
		// 验证怪物是否和配置的一样
		ArrayList<Integer> idList = MountainReferenceManager.getCopyReference(id).getMonsterIdList().get(gameCopyRecord.getProgress());
		boolean isRight = true;

		if (!idList.contains((int) getCombatModule().getEnemyId()[0])) {
			isRight = false;
		}

		if (isRight) {
			skillRound = getCombatModule().getSkillRound();
			remainMP = getCombatModule().getRemainMP();
			if (getCombatModule().getResult() == CombatModuleGeneral.RESULT_ME_WIN) {
				// 怪物掉落物品，获得奖励
				ArrayList<Integer> listIds = MountainReferenceManager.getCopyReference(id).getMonsterIdList().get(gameCopyRecord.getProgress());
				if (gameCopyRecord.getProgress() < MountainReferenceManager.getCopyReference(id).getMonsterIdList().size()) {
					DropResult dr = new DropResult();
					for (int redId : listIds) {
						PartnerReferenceManager.getPartnerRef(redId).drop(player, dr);
					}
					player.log("副本杀怪发生掉落：" + dr);

					// 判断副本是否完成
					player.log("当前进度：" + gameCopyRecord.getProgress() + "   最大进度：" + copyReference.getMaxProcess());
					if (gameCopyRecord.getProgress() == copyReference.getMaxProcess()) {
						// 成就接口
						AchievementReferenceManager.achievementEventRecord(player, AchievementTypeDefine.ACHIEVEMENT_EVENT_TYPE_COMPLETE_COPY, gameCopyRecord.getId(), 1);
						// 统计所有副本完成记录
						AchievementReferenceManager.achievementEventRecord(player, AchievementTypeDefine.ACHIEVEMENT_EVENT_TYPE_COMPLETE_COPY, 0, 1);

						AchievementReferenceManager.achievementEventRecord(player, AchievementTypeDefine.ACHIEVEMENT_EVENT_TYPE_PLAYER_ACTION_COUNT, getReference().isEliteCopy() ? AchievementTypeDefine.ACHIEVEMENT_ACTION_COPY_ELITE : AchievementTypeDefine.ACHIEVEMENT_ACTION_COPY_COMMON, NumberDefine.SHORT_ONE);

						DropControlReferenceManager.drop(getReference().getDropId(), dr);
						player.log("完成副本奖励发生掉落,掉落ID：" + getReference().getDropId() + " " + dr);

						if(pushId>0){
							FriendManager.addFriendPoints(player, pushId);
						}

						// 评星记分数
						byte mark = 1;
						int myMaxHp = getCombatModule().getMyMaxHp();
						int hpLeft = getCombatModule().getPlayerLeftHp();
						player.log("剩hp:" + hpLeft + "   原HP：" + myMaxHp);
						if (hpLeft >= myMaxHp * copyReference.getMark3() / 100) {
							mark = 3;
						} else if (hpLeft >= myMaxHp * copyReference.getMark2() / 100) {
							mark = 2;
						}
						if (gameCopyRecord.getMark() < mark) {
							gameCopyRecord.setMark(mark);
						}
						// 扣除体力
						player.reducePower((short) copyReference.getPower());
						enterTimes++;
						player.log("扣除体力：" + copyReference.getPower() + "  扣除后玩家的体力:" + player.getBaseProperty().getPower());

						if (firstPass) {
							firstPass = false;
							for (Integer id : getReference().getFirstPassDropIdList()) {
								DropControlReferenceManager.drop(id, dr);
								player.log("首次完成副本发生掉落,掉落ID：" + getReference().getDropId() + " " + dr);
							}
						}
						Mountain currentMountain = gameCopyRecordCollection.getMountain(MountainReferenceManager.getMountainId(id));
						int nextMountainId = MountainReferenceManager.getMountainsReference(currentMountain.getId()).getNextMid();

						if (copyReference.getNextCopyId() > 0 && !gameCopyRecordCollection.copyHasOpen(currentMountain.getId(), copyReference.getNextCopyId())) {// 开通下一个副本
							currentMountain.getGameCopyRecord(copyReference.getNextCopyId()).setLocked(false);
							IoBuffer iob = Utils.getIoBuffer();
							iob.putInt(currentMountain.getId());
							iob.putInt(copyReference.getNextCopyId());
							GeneralResponseEvent.send(player, iob, DefineCopyEvent.OPEN_COPY_RESPONSE_EVENT);
							player.log("开通下一副本ID：" + copyReference.getNextCopyId());
							player.getGameCopyRecordCollection().setLastCopyId((short) copyReference.getNextCopyId());// 记录当前开通的副本Id--余昆加
						}

						// 开通下一个山脉
						if (currentMountain.isAllGeneralCopyFinish() && nextMountainId > 0 && gameCopyRecordCollection.getAllMountain().get(nextMountainId) == null) {
							if (MountainReferenceManager.getMountainsReference(nextMountainId).getLevelLimit() <= player.getLevel()) {
								gameCopyRecordCollection.getMountain(nextMountainId).setLocked(false);
								IoBuffer iob = Utils.getIoBuffer();
								iob.putInt(nextMountainId);
								int copyId = gameCopyRecordCollection.getMountain(nextMountainId).getGameCopyRecords().keySet().iterator().next();
								iob.putInt(copyId);
								player.log("开通下一山脉ID：" + nextMountainId);
								player.getGameCopyRecordCollection().setLastCopyId((short) copyId);// 记录当前开通的副本Id--余昆加
								GeneralResponseEvent.send(player, iob, DefineCopyEvent.OPEN_COPY_RESPONSE_EVENT);
							}
						}
						// 怪物掉落物品，获得奖励
						ioBuffer = dr.toIoBuffer();
						dropResult.addDropResult(dr);
						dropResult.addToPlayer(player);
						new EventLog(player, getSceneId()).save(dr);
						ioBuffer.put((byte) 1);// 是否结束消息
						ioBuffer.put(mark);

						// 本山脉普通副本是否已完成
						int finishNum = 0;
						List<Integer> idsList = MountainReferenceManager.getMountainsReference(currentMountain.getId()).getGeneralCopyIds();
						for (Integer id : idsList) {
							GameCopyRecord gcr = currentMountain.getGameCopyRecords().get(id);
							if (gcr != null && gcr.isLocked) {
								finishNum++;
							}
						}
						currentMountain.setFinishCopyNum(finishNum);
						currentMountain.setGeneralCopyFinished(finishNum == idsList.size());

						gameCopyRecord.resetProgress();
						player.setCombatScene(null);
					} else {
						dropResult.addDropResult(dr);
						ioBuffer = dr.toIoBuffer();
						ioBuffer.put((byte) 0);// 是否结束消息
						ioBuffer.put((byte) 0);// 星 级
						gameCopyRecord.nextProgress();
					}
				}
				player.log("杀死boss后的进度：" + gameCopyRecord.getProgress());
			} else {
				dropResult.clear();
				ioBuffer = dropResult.toIoBuffer();
				ioBuffer.put((byte) 1);// 是否结束消息
				ioBuffer.put((byte) 0);// 星级
				gameCopyRecord.resetProgress();
				player.setCombatScene(null);
			}
			ioBuffer.put(enterTimes);
			if (pushId>0&&!player.getFriendProperty().isMyFriend(pushId) && !player.getGameCopyRecordCollection().containFriendInvite(pushId)) {
				IoBufferUtil.putBoolean(ioBuffer, true);
			} else {
				IoBufferUtil.putBoolean(ioBuffer, false);
			}
			nextProgressInit(player,true);
			GeneralResponseEvent.send(event, ioBuffer, DefineCopyEvent.GAME_COPY_DROP_INFO_RESPONSE_EVENT);
		} else {
			player.log("怪物不一致，验证出错:" + isRight);
			player.log("传进的IDS:" + getCombatModule().getEnemyId()[0]);
			player.log("配置的的IDS:" + idList);
		}
	}

	@Override
	public byte getSceneId() {
		return CombatIndex.COPY;
	}

	@JSONField(serialize = false)
	public CopyReference getReference() {
		return MountainReferenceManager.getCopyReference(id);
	}

	@Override
	public IoBuffer breakLineInfo(Player player) {
		player.log("普通副本breakLineInfo");
		checkReset(player);
		IoBuffer ioBuffer = null;
		ioBuffer = Utils.getIoBuffer();
		ioBuffer.putInt(id);
		FriendManager.packFriendProperty(ioBuffer, player, pushId);
		return ioBuffer;
	}

	public void nextProgress() {
		progress++;
	}

	public void resetProgress() {
		progress = 0;
		remainMP = -1;
		skillRound = null;
	}

	public String toString() {
		return "副本信息: ==== id:" + id + "|mark:" + mark + "|lastEnterTime:" + lastEnterTime + "|progress:" + progress;
	}

	public long getLastEnterTime() {
		return lastEnterTime;
	}

	public byte getMark() {
		return mark;
	}

	public void setMark(byte mark) {
		this.mark = mark;
	}

	public int getProgress() {
		return progress;
	}

	public void setProgress(int progress) {
		this.progress = progress;
	}

	public boolean isLocked() {
		return isLocked;
	}

	public DropResult getDropResult() {
		if (dropResult == null) {
			dropResult = new DropResult();
		}
		return dropResult;
	}

	public void setDropResult(DropResult dropResult) {
		this.dropResult = dropResult;
	}

	public void setLocked(boolean isLocked) {
		this.isLocked = isLocked;
	}

	public byte getBuyTimes() {
		return buyTimes;
	}

	public void setBuyTimes(byte buyTimes) {
		this.buyTimes = buyTimes;
	}

	public boolean isFinished() {
		return mark > 0 ? true : false;
	}

	public void setLastEnterTime(long lastEnterTime) {
		this.lastEnterTime = lastEnterTime;
	}

	public long getPushId() {
		return pushId;
	}

	public byte getEnterTimes() {
		return enterTimes;
	}

	public boolean isFirstPass() {
		return firstPass;
	}

	public void setFirstPass(boolean firstPass) {
		this.firstPass = firstPass;
	}

	public void setEnterTimes(byte enterTimes) {
		this.enterTimes = enterTimes;
	}

	public void setPushId(long pushId) {
		this.pushId = pushId;
	}
}
