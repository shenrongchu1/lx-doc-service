package com.laxqnsys.core.sys.ao;

import com.laxqnsys.core.sys.model.vo.UserInfoUpdateVO;
import com.laxqnsys.core.sys.model.vo.UserInfoVO;
import com.laxqnsys.core.sys.model.vo.UserLoginVO;
import com.laxqnsys.core.sys.model.vo.UserPwdModifyVO;
import com.laxqnsys.core.sys.model.vo.UserRegisterVO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author wuzhenhong
 * @date 2024/5/14 11:07
 */
public interface SysUserInfoAO {

    void register(UserRegisterVO userRegisterVO);

    void login(UserLoginVO userLoginVO, HttpServletRequest request, HttpServletResponse response);

    void logout(HttpServletRequest request, HttpServletResponse response);

    UserInfoVO getUserInfo();

    void updateUserInfo(UserInfoUpdateVO userInfoUpdateVO);

    void changePassword(UserPwdModifyVO userPwdModifyVO);
}
