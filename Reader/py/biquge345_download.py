import requests
from bs4 import BeautifulSoup
from urllib.parse import urljoin
import time


# ======================
# 配置
# ======================

BOOK_URL = "https://www.biquge345.com/book/45863/"

OUTPUT_FILE = "补充章节.txt"


HEADERS = {
    "User-Agent":
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 Chrome/150 Safari/537.36",

    "Referer":
        "https://www.biquge345.com/"
}


session = requests.Session()
session.headers.update(HEADERS)



# ======================
# 获取网页
# ======================

def get_html(url):

    print("请求:", url)

    r = session.get(
        url,
        timeout=15
    )


    if r.status_code != 200:

        print(
            "失败:",
            r.status_code
        )

        return None


    r.encoding = "utf-8"

    return r.text



# ======================
# 获取章节列表
# ======================

def get_chapter_list():

    html = get_html(
        BOOK_URL
    )


    if not html:
        return []


    soup = BeautifulSoup(
        html,
        "lxml"
    )


    chapters = []


    # li > a

    for a in soup.select(
        "li a"
    ):


        title = a.get(
            "title"
        )


        href = a.get(
            "href"
        )


        if not href:
            continue


        url = urljoin(
            BOOK_URL,
            href
        )


        chapters.append(
            {
                "title": title,
                "url": url
            }
        )


    return chapters



# ======================
# 获取正文
# ======================

def get_content(url):

    html = get_html(
        url
    )


    if not html:
        return ""


    soup = BeautifulSoup(
        html,
        "lxml"
    )


    content = soup.find(
        "div",
        id="txt"
    )


    if not content:

        print(
            "正文不存在"
        )

        return ""



    # 删除广告
    for x in content.find_all(
        ["script","style"]
    ):
        x.decompose()



    return content.get_text(
        "\n",
        strip=True
    )



# ======================
# 下载
# ======================

def download():


    chapters = get_chapter_list()


    print(
        "发现章节:",
        len(chapters)
    )


    # 找第153章开始

    start = False


    with open(
        OUTPUT_FILE,
        "w",
        encoding="utf-8"
    ) as f:



        for ch in chapters:


            title = ch["title"]


            if not start:


                if title and title.startswith(
                    "第153章"
                ):

                    start = True

                else:
                    continue



            print(
                title
            )


            text = get_content(
                ch["url"]
            )


            f.write(
                "\n\n"
                + title
                + "\n\n"
            )


            f.write(
                text
            )


            time.sleep(
                0.5
            )


    print(
        "完成:",
        OUTPUT_FILE
    )



if __name__ == "__main__":

    download()