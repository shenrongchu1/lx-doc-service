package com.laxqnsys.core.interceptor;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.laxqnsys.common.enums.ErrorCodeEnum;
import com.laxqnsys.common.exception.BusinessException;
import com.laxqnsys.core.constants.CommonCons;
import com.laxqnsys.core.context.LoginContext;
import com.laxqnsys.core.enums.EquipmentTypeEnum;
import com.laxqnsys.core.manager.service.UserLoginManager;
import com.laxqnsys.core.sys.model.bo.UserInfoBO;
import com.laxqnsys.core.sys.model.vo.UserLoginVO;
import com.laxqnsys.core.util.web.WebUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * @author wuzhenhong
 * @date 2024/5/14 8:48
 */
public class LoginHandlerInterceptor implements HandlerInterceptor {

    private List<String> whiteUrlList = Lists.newArrayList();
    private AntPathMatcher antPathMatcher = new AntPathMatcher();
    private UserLoginManager userLoginManager;
    private final String MOBILE_EQUIPMENT = "android|iphone|ipad|ipod|blackberry|iemobile|opera mini";
    private final String PC = "Windows";

    public LoginHandlerInterceptor(UserLoginManager userLoginManager) {
        this.userLoginManager = userLoginManager;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String uri = request.getRequestURI();
        boolean match = whiteUrlList.stream().anyMatch(url -> antPathMatcher.match(url, uri));
        if (match) {
            if (uri.contains("/api/login")) {
                // 使用 CachedBodyHttpServletRequest 包装原始的 HttpServletRequest
                CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);

                // 判断设备类型
                judgeEquipmentType(cachedRequest);

                // 将包装后的请求传递给后续的处理器
                request = cachedRequest;
            }
            return true;
        }
        String token = WebUtil.getCookie(request, CommonCons.TOKEN_KEY);
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ErrorCodeEnum.UN_LOGIN.getCode(), ErrorCodeEnum.UN_LOGIN.getDesc());
        }
        String userJsonInfo = userLoginManager.get(token);
        if (!StringUtils.hasText(userJsonInfo)) {
            throw new BusinessException(ErrorCodeEnum.UN_LOGIN.getCode(), ErrorCodeEnum.UN_LOGIN.getDesc());
        }
        UserInfoBO userInfoBO = JSONUtil.toBean(userJsonInfo, UserInfoBO.class);
        LoginContext.setUserInfo(userInfoBO);
        // 续期
        userLoginManager.expire(token, CommonCons.LOGIN_EXPIRE_SECONDS);
        String key = CommonCons.LOGIN_USER_TOKE_KEY + LoginContext.getUserId();
        userLoginManager.expire(key, CommonCons.LOGIN_TOKEN_EXPIRE_SECONDS);
        return true;
    }

    private void judgeEquipmentType(HttpServletRequest request) throws IOException {

        String header = request.getHeader("user-agent");

        if (StringUtils.isEmpty(header)) {
            throw new BusinessException(ErrorCodeEnum.ILLEGAL_EQUIPMENT.getCode(), ErrorCodeEnum.ILLEGAL_EQUIPMENT.getDesc());
        }

        boolean isMobile = Pattern.compile(MOBILE_EQUIPMENT, Pattern.CASE_INSENSITIVE).matcher(header).find();
        boolean isPC = Pattern.compile(PC, Pattern.CASE_INSENSITIVE).matcher(header).find();;

        // 读取请求体
        StringBuilder requestBody = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                requestBody.append(line);
            }
        }

        // 将请求体转换为 User 对象
        ObjectMapper objectMapper = new ObjectMapper();
        UserLoginVO user = objectMapper.readValue(requestBody.toString(), UserLoginVO.class);

        if (isMobile) {
            user.setEquipmentType(EquipmentTypeEnum.MOBLIE.getCode());
        }else if (isPC) {
            user.setEquipmentType(EquipmentTypeEnum.PC.getCode());
        }else {
            throw new BusinessException(ErrorCodeEnum.ILLEGAL_EQUIPMENT.getCode(), ErrorCodeEnum.ILLEGAL_EQUIPMENT.getDesc());
        }

        // 将User对象写入req
        request.setAttribute("userLoginVO", user);
    }

    public void addWhiteUrl(String url) {
        whiteUrlList.add(url);
    }
}
