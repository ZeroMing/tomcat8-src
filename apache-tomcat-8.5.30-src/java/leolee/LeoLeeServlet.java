package leolee;

import org.apache.catalina.startup.Tomcat;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.UUID;

/**
 * 测试Servlet
 * @author: LeoLee
 * @date: 2019/8/30 09:29
 */
public class LeoLeeServlet extends HttpServlet {


    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doPost(req,resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        String name = req.getParameter("name");
        resp.getWriter().write("hello,"+name);
    }

    public static void main(String[] args) {
        /**
         * 通过数字 转为 补位00的 字符串
         * @param num
         * @return
         */
        //System.out.println(getNumber(100000));
        //限制组合数
        UUID uuid = UUID.randomUUID();
        System.out.println(uuid.toString().replace("-",""));

    }

    public static String getNumber(long num){
        NumberFormat formatter = NumberFormat.getNumberInstance();
        formatter.setMinimumIntegerDigits(6);
        formatter.setGroupingUsed(false);
        String number = formatter.format(num);
        return number;
    }
}
