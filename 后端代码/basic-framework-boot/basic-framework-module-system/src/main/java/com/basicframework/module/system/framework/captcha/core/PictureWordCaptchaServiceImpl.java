package com.basicframework.module.system.framework.captcha.core;

import cn.hutool.core.util.RandomUtil;
import com.anji.captcha.model.common.RepCodeEnum;
import com.anji.captcha.model.common.ResponseModel;
import com.anji.captcha.model.vo.CaptchaVO;
import com.anji.captcha.service.CaptchaCacheService;
import com.anji.captcha.service.impl.AbstractCaptchaService;
import com.anji.captcha.service.impl.CaptchaServiceFactory;
import com.anji.captcha.util.ImageUtils;
import com.anji.captcha.util.RandomUtils;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.security.SecureRandom;
import java.util.Properties;
import org.apache.commons.lang3.Strings;

/**
 * 图片文字验证码
 *
 * @since 2025/7/23 20:44
 */
public class PictureWordCaptchaServiceImpl extends AbstractCaptchaService {

    /**
     * 验证码的基础字符
     */
    private static final String CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    /**
     * 验证码长度
     */
    private static final int LENGTH = 4;

    private static final int MAX_CAPTCHA_TEXT_LENGTH = 64;
    private static final SecureRandom CAPTCHA_RANDOM = new SecureRandom();

    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final int LINES = 10;

    @Override
    public void init(Properties config) {
        super.init(config);
    }

    @Override
    public void destroy(Properties config) {
        logger.info("start-clear-history-data-{}", captchaType());
    }

    @Override
    public String captchaType() {
        return "pictureWord";
    }

    @Override
    public ResponseModel get(CaptchaVO captchaVO) {
        String text = generateRandomText(LENGTH);
        CaptchaVO imageData = getImageData(text);
        return ResponseModel.successData(imageData);
    }

    @Override
    public ResponseModel check(CaptchaVO captchaVO) {
        ResponseModel r = super.check(captchaVO);
        if (!validatedReq(r)) {
            return r;
        }

        // 原子消费验证码，避免并发请求重放同一令牌。
        String codeKey = String.format(REDIS_CAPTCHA_KEY, captchaVO.getToken());
        String codeValue = consumeCaptcha(CaptchaServiceFactory.getCache(cacheType), codeKey);
        if (codeValue == null) {
            return ResponseModel.errorMsg(RepCodeEnum.API_CAPTCHA_INVALID);
        }
        String[] codeAndSecret = codeValue.split(",", 2);
        if (codeAndSecret.length != 2 || codeAndSecret[0].isBlank()) {
            return ResponseModel.errorMsg(RepCodeEnum.API_CAPTCHA_INVALID);
        }
        String code = codeAndSecret[0];

        // 用户输入的验证码(CaptchaVO 中 没有预留字段，暂时用 pointJson 无需加解密)
        String userCode = captchaVO.getPointJson();
        if (!Strings.CI.equals(code, userCode)) {
            afterValidateFail(captchaVO);
            return ResponseModel.errorMsg(RepCodeEnum.API_CAPTCHA_COORDINATE_ERROR);
        }

        // 校验成功，将信息存入缓存
        String value = captchaVO.getToken().concat("---").concat(userCode);
        String secondKey = String.format(REDIS_SECOND_CAPTCHA_KEY, value);
        CaptchaServiceFactory.getCache(cacheType).set(secondKey, captchaVO.getToken(), EXPIRESIN_THREE);
        captchaVO.setResult(true);
        captchaVO.resetClientFlag();
        return ResponseModel.successData(captchaVO);
    }

    @Override
    public ResponseModel verification(CaptchaVO captchaVO) {
        ResponseModel r = super.verification(captchaVO);
        if (!validatedReq(r)) {
            return r;
        }
        try {
            String codeKey = String.format(REDIS_SECOND_CAPTCHA_KEY, captchaVO.getCaptchaVerification());
            if (consumeCaptcha(CaptchaServiceFactory.getCache(cacheType), codeKey) == null) {
                return ResponseModel.errorMsg(RepCodeEnum.API_CAPTCHA_INVALID);
            }
        } catch (Exception e) {
            logger.error("验证码解析失败，异常类型：{}", e.getClass().getName());
            return ResponseModel.errorMsg(RepCodeEnum.API_CAPTCHA_ERROR);
        }
        return ResponseModel.success();
    }

    private CaptchaVO getImageData(String text) {
        CaptchaVO dataVO = new CaptchaVO();
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        // 设置背景色
        g.setColor(getRandomColor(200, 250));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        // 绘制干扰线
        for (int i = 0; i < LINES; i++) {
            g.setColor(getRandomColor(100, 200));
            int x1 = RandomUtil.randomInt(WIDTH);
            int y1 = RandomUtil.randomInt(HEIGHT);
            int x2 = RandomUtil.randomInt(WIDTH);
            int y2 = RandomUtil.randomInt(HEIGHT);
            g.drawLine(x1, y1, x2, y2);
        }
        // 设置字体
        g.setFont(new Font("Arial", Font.BOLD, 24));
        // 绘制验证码文本
        for (int i = 0; i < text.length(); i++) {
            g.setColor(getRandomColor(20, 130));
            // 文字旋转
            AffineTransform affineTransform = new AffineTransform();
            int x = 20 + i * 20;
            int y = 24 + RandomUtil.randomInt(8);
            // 旋转范围 -45 ~ 45
            affineTransform.setToRotation(Math.toRadians(RandomUtil.randomInt(-45, 45)), x, y);
            g.setTransform(affineTransform);
            g.drawString(text.charAt(i) + "", x, y);
        }
        // 添加噪点
        for (int i = 0; i < 100; i++) {
            int x = RandomUtil.randomInt(WIDTH);
            int y = RandomUtil.randomInt(HEIGHT);
            image.setRGB(x, y, getRandomColor(0, 255).getRGB());
        }
        g.dispose();

        dataVO.setSecretKey(null);

        dataVO.setOriginalImageBase64(ImageUtils.getImageToBase64Str(image).replaceAll("\r|\n", ""));
        dataVO.setToken(RandomUtils.getUUID());
        // 将坐标信息存入 redis 中
        String codeKey = String.format(REDIS_CAPTCHA_KEY, dataVO.getToken());
        CaptchaServiceFactory.getCache(cacheType).set(codeKey, getCodeValue(text), EXPIRESIN_SECONDS);
        return dataVO;
    }

    private String getCodeValue(String text) {
        return text + ",";
    }

    private static String consumeCaptcha(CaptchaCacheService cache, String key) {
        if (cache instanceof RedisCaptchaServiceImpl redisCache) {
            return redisCache.getAndDelete(key);
        }
        synchronized (cache) {
            String value = cache.get(key);
            if (value != null) {
                cache.delete(key);
            }
            return value;
        }
    }

    private Color getRandomColor(int min, int max) {
        int minVal = Math.min(min, max);
        int maxVal = Math.max(min, max);
        int r = RandomUtil.randomInt(minVal, maxVal);
        int g = RandomUtil.randomInt(minVal, maxVal);
        int b = RandomUtil.randomInt(minVal, maxVal);
        return new Color(r, g, b);
    }

    /**
     * 使用密码学随机源生成指定长度的验证码文本。
     *
     * @param length 长度
     * @return 由验证码字符集组成的随机文本
     */
    public static String generateRandomText(int length) {
        if (length < 1 || length > MAX_CAPTCHA_TEXT_LENGTH) {
            throw new IllegalArgumentException("验证码长度必须在 1 到 64 之间");
        }
        StringBuilder text = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            text.append(CHARACTERS.charAt(CAPTCHA_RANDOM.nextInt(CHARACTERS.length())));
        }
        return text.toString();
    }
}
