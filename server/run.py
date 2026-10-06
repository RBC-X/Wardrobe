import argparse
import uvicorn
from wardrobe.app import create_app

if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--port",type=int,default=8765)
    args=parser.parse_args()
    app=create_app()
    server=uvicorn.Server(uvicorn.Config(app,host="127.0.0.1",port=args.port,access_log=False,workers=1))
    app.state.shutdown=lambda:setattr(server,"should_exit",True)
    server.run()
