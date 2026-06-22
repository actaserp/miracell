package mes.app.udi;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

import mes.domain.entity.User;

/**
 * UDI 화면 라우팅 컨트롤러.
 * 로그인 사용자만 접근 가능(SecurityConfiguration.anyRequest().authenticated()).
 *
 *   /udi/delivery -> 납품보고
 *   /udi/return   -> 반품보고
 *   /udi/disposal -> 폐기보고
 *   /udi/summary  -> 현황집계표
 */
@Controller
public class UdiPageController {

	@GetMapping("/udi/delivery")
	public ModelAndView deliveryReport(Authentication auth) {
		return view("udi/udi_delivery_report", auth);
	}

	@GetMapping("/udi/return")
	public ModelAndView returnReport(Authentication auth) {
		return view("udi/udi_return_report", auth);
	}

	@GetMapping("/udi/disposal")
	public ModelAndView disposalReport(Authentication auth) {
		return view("udi/udi_disposal_report", auth);
	}

	@GetMapping("/udi/summary")
	public ModelAndView summary(Authentication auth) {
		return view("udi/udi_summary", auth);
	}

	private ModelAndView view(String viewName, Authentication auth) {
		ModelAndView mv = new ModelAndView();
		mv.setViewName(viewName);
		if (auth != null && auth.getPrincipal() instanceof User user) {
			mv.addObject("userinfo", user);
			mv.addObject("username", user.getUserProfile() != null ? user.getUserProfile().getName() : "");
		}
		// 메뉴 권한 연동 전 기본값. 추후 GuiController 권한 루틴과 통합 가능.
		mv.addObject("read_flag", true);
		mv.addObject("write_flag", true);
		return mv;
	}
}
