package com.laxqnsys.core.sys.ao.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laxqnsys.common.enums.ErrorCodeEnum;
import com.laxqnsys.common.exception.BusinessException;
import com.laxqnsys.common.util.AESUtil;
import com.laxqnsys.core.constants.CommonCons;
import com.laxqnsys.core.constants.RedissonLockPrefixCons;
import com.laxqnsys.core.context.LoginContext;
import com.laxqnsys.core.enums.EquipmentTypeEnum;
import com.laxqnsys.core.enums.UserStatusEnum;
import com.laxqnsys.core.manager.service.UserLoginManager;
import com.laxqnsys.core.sys.ao.SysUserInfoAO;
import com.laxqnsys.core.sys.dao.entity.SysUserInfo;
import com.laxqnsys.core.sys.model.bo.UserInfoBO;
import com.laxqnsys.core.sys.model.vo.UserInfoUpdateVO;
import com.laxqnsys.core.sys.model.vo.UserInfoVO;
import com.laxqnsys.core.sys.model.vo.UserLoginVO;
import com.laxqnsys.core.sys.model.vo.UserPwdModifyVO;
import com.laxqnsys.core.sys.model.vo.UserRegisterVO;
import com.laxqnsys.core.sys.service.ISysUserInfoService;
import com.laxqnsys.core.util.web.WebUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * @author wuzhenhong
 * @date 2024/5/14 11:07
 */
@Service
public class SysUserInfoAOImpl implements SysUserInfoAO {

    private static final Object OBJECT = new Object();
    private static final Map<String, Object> LOCK = new ConcurrentHashMap<>();

    @Autowired
    private ISysUserInfoService sysUserInfoService;

    @Autowired
    private UserLoginManager userLoginManager;

    @Override
    public void register(UserRegisterVO userRegisterVO) {

        long count = sysUserInfoService.count(Wrappers.<SysUserInfo>lambdaQuery()
                .eq(SysUserInfo::getAccount, userRegisterVO.getAccount()));
        if (count > 0L) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), String.format("名为%s的账户已存在，请设置其他的账户名！", userRegisterVO.getAccount()));
        }

        String lockKey = RedissonLockPrefixCons.USER_REGISTER + "_" + userRegisterVO.getAccount();
        while (LOCK.putIfAbsent(lockKey, OBJECT) != null) {
            Thread.yield();
        }
        try {
            long c = sysUserInfoService.count(Wrappers.<SysUserInfo>lambdaQuery()
                    .eq(SysUserInfo::getAccount, userRegisterVO.getAccount()));
            if (c > 0L) {
                throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), String.format("名为%s的账户已存在，请设置其他的账户名！", userRegisterVO.getAccount()));
            }
            SysUserInfo userInfo = new SysUserInfo();
            userInfo.setAccount(userRegisterVO.getAccount());
            String pwd = AESUtil.encrypt(userRegisterVO.getPassword(), CommonCons.AES_KEY);
            userInfo.setPassword(pwd);
            userInfo.setCreateAt(LocalDateTime.now());
            userInfo.setVersion(0);
            userInfo.setUpdateAt(LocalDateTime.now());
            userInfo.setStatus(UserStatusEnum.NORMAL.getStatus());
            sysUserInfoService.save(userInfo);
        } finally {
            LOCK.remove(lockKey);
        }

    }

    @Override
    public void login(UserLoginVO userLoginVO, HttpServletRequest request, HttpServletResponse response) {

        String password = userLoginVO.getPassword();
        String pwd = AESUtil.encrypt(password, CommonCons.AES_KEY);
        SysUserInfo userInfo = sysUserInfoService.getOne(Wrappers.<SysUserInfo>lambdaQuery()
                .eq(SysUserInfo::getAccount, userLoginVO.getAccount())
                .eq(SysUserInfo::getPassword, pwd));
        if (Objects.isNull(userInfo)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "用户名或者密码错误！");
        }

        this.userStatusCheck(userInfo);

        // 判断设备类型
        Integer equipmentType = this.judgeEquipmentType(request);

        // 踢人
        String key = this.downOldLogin(userInfo.getId(), equipmentType);

        String token = UUID.randomUUID().toString().replace("-", "");
        UserInfoBO userInfoBO = new UserInfoBO();
        userInfoBO.setAccount(userInfo.getAccount());
        userInfoBO.setId(userInfo.getId());
        userLoginManager.set(token, JSONUtil.toJsonStr(userInfoBO), CommonCons.LOGIN_EXPIRE_SECONDS);
        userLoginManager.set(key, token, CommonCons.LOGIN_TOKEN_EXPIRE_SECONDS);
        WebUtil.saveCookie(response, token, CommonCons.LOGIN_EXPIRE_SECONDS);
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String token = WebUtil.getCookie(request, CommonCons.TOKEN_KEY);
        if (!StringUtils.hasText(token)) {
            return;
        }
        WebUtil.saveCookie(response, token, 0);
        userLoginManager.delete(token);
        String key = CommonCons.LOGIN_USER_TOKE_KEY + LoginContext.getUserId();
        userLoginManager.delete(key);
    }

    @Override
    public UserInfoVO getUserInfo() {
        // 获取当前登录人信息
        Long id = LoginContext.getUserId();
        if (Objects.isNull(id)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "获取当前登录人信息失败！");
        }
        SysUserInfo userInfo = sysUserInfoService.getById(id);
        if (Objects.isNull(userInfo)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                    String.format("未获取到id为%s的登录人信息！", id));
        }

        this.userStatusCheck(userInfo);

        UserInfoVO userInfoVO = new UserInfoVO();
        userInfoVO.setId(userInfo.getId());
        userInfoVO.setAccount(userInfo.getAccount());
        userInfoVO.setUserName(userInfo.getUserName());
        userInfoVO.setAvatar(userInfo.getAvatar());
        userInfoVO.setCreateAt(userInfo.getCreateAt());
        return userInfoVO;
    }

    @Override
    public void updateUserInfo(UserInfoUpdateVO userInfoUpdateVO) {
        Long userId = LoginContext.getUserId();
        SysUserInfo userInfo = sysUserInfoService.getById(userId);
        if (Objects.isNull(userInfo)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                    String.format("未获取到id为%s的登录人信息！", userId));
        }
        this.userStatusCheck(userInfo);
        SysUserInfo update = new SysUserInfo();
        update.setId(userId);
        update.setUserName(userInfoUpdateVO.getUserName());
        update.setAvatar(userInfoUpdateVO.getAvatar());
        sysUserInfoService.updateById(update);
    }

    @Override
    public void changePassword(UserPwdModifyVO userPwdModifyVO) {
        Long userId = LoginContext.getUserId();
        SysUserInfo sysUserInfo = sysUserInfoService.getById(userId);
        if (Objects.isNull(sysUserInfo)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                    String.format("未获取到id为%s的登录人信息！", userId));
        }
        String oldPassword = userPwdModifyVO.getOldPassword();
        String password = sysUserInfo.getPassword();
        if (!password.equals(AESUtil.encrypt(oldPassword, CommonCons.AES_KEY))) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "原秘密输入不正确！");
        }
        String newPassword = userPwdModifyVO.getNewPassword();
        String newPwd = AESUtil.encrypt(newPassword, CommonCons.AES_KEY);
        SysUserInfo update = new SysUserInfo();
        update.setId(userId);
        update.setPassword(newPwd);
        sysUserInfoService.updateById(update);
    }

    private String downOldLogin(Long userId, Integer equipmentType) {

        String key = CommonCons.LOGIN_USER_TOKE_KEY + userId + equipmentType;
        String oldToken = userLoginManager.get(key);
        if (StringUtils.hasText(oldToken)) {
            // 踢掉其他的登录信息
            userLoginManager.delete(oldToken);
        }
        return key;
    }

    private void userStatusCheck(SysUserInfo userInfo) {

        if (UserStatusEnum.DISABLED.getStatus().equals(userInfo.getStatus())) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "当前用户已被禁用！");
        }

        if (UserStatusEnum.DELETE.getStatus().equals(userInfo.getStatus())) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "当前用户已注销！");
        }
    }

    private Integer judgeEquipmentType(HttpServletRequest request) {
        String MOBILE_EQUIPMENT = "android|iphone|ipad|ipod|blackberry|iemobile|opera mini";
        String PC = "Windows";

        String header = request.getHeader("user-agent");

        if (StringUtils.isEmpty(header)) {
            throw new BusinessException(ErrorCodeEnum.ILLEGAL_EQUIPMENT.getCode(), ErrorCodeEnum.ILLEGAL_EQUIPMENT.getDesc());
        }

        boolean isMobile = Pattern.compile(MOBILE_EQUIPMENT, Pattern.CASE_INSENSITIVE).matcher(header).find();

        boolean isPC = Pattern.compile(PC, Pattern.CASE_INSENSITIVE).matcher(header).find();;

        if (isMobile) {
            return EquipmentTypeEnum.MOBLIE.getCode();
        }else if (isPC) {
            return EquipmentTypeEnum.PC.getCode();
        }else {
            throw new BusinessException(ErrorCodeEnum.ILLEGAL_EQUIPMENT.getCode(), ErrorCodeEnum.ILLEGAL_EQUIPMENT.getDesc());
        }
    }
}
